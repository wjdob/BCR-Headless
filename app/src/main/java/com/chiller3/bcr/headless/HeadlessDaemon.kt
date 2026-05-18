/*
 * SPDX-FileCopyrightText: 2026 wjdob
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:Suppress("DEPRECATION")

package com.chiller3.bcr.headless

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import com.chiller3.bcr.output.CallDirection
import java.io.File

data class HeadlessConfig(
    val moduleDir: File,
    val outputDir: File,
    val minDurationSeconds: Int,
    val logEnabled: Boolean,
    val notificationsEnabled: Boolean,
    val stereoEnabled: Boolean,
)

class HeadlessDaemon(
    private val context: Context,
    private val config: HeadlessConfig,
) : HeadlessRecorderSession.Listener {
    private val stateDir = File(config.moduleDir, ".state")
    private val configStore = ModuleConfigStore(File(config.moduleDir, ".config"))
    private val statusWriter = StatusWriter(File(stateDir, "runtime.env"))
    private val recordingLogStore = RecordingLogStore(File(stateDir, "recording-log.json"))
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private val telecomManager = context.getSystemService(TelecomManager::class.java)
    private val notifier = HeadlessNotifier(config.notificationsEnabled)
    private val actionScript = File(config.moduleDir, "action.sh")

    private var previousCallState = TelephonyManager.CALL_STATE_IDLE
    private var recorder: HeadlessRecorderSession? = null
    private var lastPollState = TelephonyManager.CALL_STATE_IDLE
    private var lastPollSource = "poll_uninitialized"
    private var lastKnownCallNumberRaw: String? = null
    @Volatile private var isCharging = false
    @Volatile private var autoQueueWorkerOwned = false

    private var modernListener: HeadlessCallStateCallback? = null
    private var legacyListener: HeadlessLegacyCallStateListener? = null
    private var broadcastReceiver: HeadlessPhoneStateReceiver? = null
    private var powerReceiver: HeadlessPowerStateReceiver? = null
    private var poller: Thread? = null
    private var autoQueueStartThread: Thread? = null
    private var autoQueueStartGeneration = 0L

    fun start() {
        stateDir.mkdirs()
        config.outputDir.mkdirs()
        recordingLogStore.ensureExists()

        // Keep runtime state explicit so UAT can distinguish "daemon is alive"
        // from "daemon can actually observe call state on this ROM".
        statusWriter.update(
            mapOf(
                "daemon.running" to "1",
                "daemon.mode" to "headless",
                "recorder.state" to "idle",
                "config.output_dir" to config.outputDir.absolutePath,
                "config.min_duration" to config.minDurationSeconds.toString(),
                "config.log_enabled" to config.logEnabled.toString(),
                "config.notifications_enabled" to config.notificationsEnabled.toString(),
                "config.stereo_enabled" to config.stereoEnabled.toString(),
                "power.charging" to "0",
                "telephony.monitor_mode" to "callback+broadcast+poll",
                "telephony.callback_registered" to "0",
                "telephony.receiver_registered" to "0",
                "telephony.poller_running" to "0",
                "last.error" to null,
            ),
        )

        checkNotNull(telephonyManager) { "TelephonyManager service is unavailable" }
        notifier.ensureChannels()

        registerPhoneStateReceiver()
        registerPowerReceiver()
        registerTelephonyListener()
        startPollingFallback()
        val automationSettings = loadAutomationSettings()
        if (automationSettings.requiresCharging) {
            maybeScheduleAutoQueueStart(automationSettings, "daemon_start")
        } else {
            startAutoQueueWorkerIfNeeded(automationSettings, "daemon_start")
        }
    }

    private fun registerPhoneStateReceiver() {
        try {
            val receiver = HeadlessPhoneStateReceiver(::onPhoneStateBroadcast)
            broadcastReceiver = receiver
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)

            // Some ROMs appear to hide TelephonyManager state from this root-side
            // process even though the daemon is alive. A dynamic PHONE_STATE
            // receiver gives us a second call-state path without reinstalling a
            // visible user app.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }

            statusWriter.update(mapOf("telephony.receiver_registered" to "1"))
            println("Registered ACTION_PHONE_STATE_CHANGED receiver")
        } catch (e: Exception) {
            statusWriter.update(
                mapOf(
                    "telephony.receiver_registered" to "0",
                    "telephony.receiver_error" to (e.localizedMessage ?: e.javaClass.simpleName),
                ),
            )
            println(
                "Phone-state broadcast unavailable on this ROM: " +
                    (e.localizedMessage ?: e.javaClass.simpleName),
            )
        }
    }

    private fun registerTelephonyListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val listener = HeadlessCallStateCallback { state ->
                    onCallStateChanged(state, "telephony_callback")
                }
                modernListener = listener
                telephonyManager.registerTelephonyCallback(context.mainExecutor, listener)
            } else {
                @Suppress("DEPRECATION")
                val listener = HeadlessLegacyCallStateListener { state ->
                    onCallStateChanged(state, "phone_state_listener")
                }
                legacyListener = listener
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }

            statusWriter.update(mapOf("telephony.callback_registered" to "1"))
            println("Registered TelephonyManager call-state listener")
        } catch (e: Exception) {
            statusWriter.update(
                mapOf(
                    "telephony.callback_registered" to "0",
                    "telephony.callback_error" to (e.localizedMessage ?: e.javaClass.simpleName),
                ),
            )
            println(
                "Telephony callback unavailable on this ROM: " +
                    (e.localizedMessage ?: e.javaClass.simpleName),
            )
        }
    }

    private fun onPhoneStateBroadcast(intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { lastKnownCallNumberRaw = it }

        val mapped = when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
            else -> return
        }

        onCallStateChanged(mapped, "phone_state_broadcast")
    }

    private fun startPollingFallback() {
        val thread = Thread(
            {
                println("Started call-state polling fallback")
                statusWriter.update(mapOf("telephony.poller_running" to "1"))

                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val sample = pollCurrentState()

                        if (sample.state != lastPollState || sample.source != lastPollSource) {
                            lastPollState = sample.state
                            lastPollSource = sample.source
                            statusWriter.update(
                                mapOf(
                                    "telephony.poll_state" to serializeCallState(sample.state),
                                    "telephony.poll_source" to sample.source,
                                ),
                            )
                        }

                        onCallStateChanged(sample.state, sample.source)
                    } catch (e: Exception) {
                        statusWriter.update(
                            mapOf(
                                "telephony.poll_error" to (e.localizedMessage ?: e.javaClass.simpleName),
                            ),
                        )
                        System.err.println("Call-state polling failed")
                        e.printStackTrace()
                    }

                    Thread.sleep(POLL_INTERVAL_MS)
                }
            },
            "HeadlessCallStatePoller",
        )
        thread.isDaemon = true
        poller = thread
        thread.start()
    }

    private fun pollCurrentState(): PolledState {
        val telephonyState = try {
            telephonyManager.callState
        } catch (e: Exception) {
            statusWriter.update(
                mapOf(
                    "telephony.poll_call_state_error" to (e.localizedMessage ?: e.javaClass.simpleName),
                ),
            )
            null
        }

        if (telephonyState != null && telephonyState != TelephonyManager.CALL_STATE_IDLE) {
            return PolledState(telephonyState, "telephony_manager_poll")
        }

        // TelecomManager state is only a fallback for ROMs where direct call-state
        // callbacks fail. Guard it behind the same runtime permission check that
        // lint expects, and keep the fallback limited to normal in-call state.
        val telecomInCall = if (
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED ||
                (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        context.checkSelfPermission(Manifest.permission.READ_BASIC_PHONE_STATE) ==
                        PackageManager.PERMISSION_GRANTED
                    )
        ) {
            try {
                telecomManager?.isInCall == true
            } catch (e: Exception) {
                statusWriter.update(
                    mapOf(
                        "telephony.telecom_poll_error" to
                            (e.localizedMessage ?: e.javaClass.simpleName),
                    ),
                )
                false
            }
        } else {
            false
        }

        if (telecomInCall) {
            return PolledState(TelephonyManager.CALL_STATE_OFFHOOK, "telecom_manager_poll")
        }

        return PolledState(telephonyState ?: TelephonyManager.CALL_STATE_IDLE, "telephony_manager_poll")
    }

    private fun onCallStateChanged(state: Int, source: String) {
        val previous = previousCallState

        if (state == previous) {
            statusWriter.update(mapOf("telephony.last_source" to source))
            return
        }

        previousCallState = state
        println("Call state changed: ${serializeCallState(previous)} -> ${serializeCallState(state)} via ${source}")

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                captureActiveCallNumber()
                statusWriter.update(
                    mapOf(
                        "telephony.call_state" to "ringing",
                        "telephony.last_source" to source,
                        "last.event" to "call-ringing",
                    ),
                )
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                statusWriter.update(
                    mapOf(
                        "telephony.call_state" to "offhook",
                        "telephony.last_source" to source,
                        "last.event" to "call-offhook",
                    ),
                )
                maybeStartRecorder(previous)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                statusWriter.update(
                    mapOf(
                        "telephony.call_state" to "idle",
                        "telephony.last_source" to source,
                        "last.event" to "call-idle",
                    ),
                )
                if (recorder != null) {
                    recorder?.requestStop()
                } else {
                    lastKnownCallNumberRaw = null
                }
            }
        }
    }

    private fun maybeStartRecorder(previousState: Int) {
        if (recorder != null) {
            return
        }

        val direction = when (previousState) {
            TelephonyManager.CALL_STATE_RINGING -> CallDirection.IN
            TelephonyManager.CALL_STATE_IDLE -> CallDirection.OUT
            else -> null
        }

        // When the daemon is driven by polling, we do not get the rich
        // in-call callbacks that the original system-app architecture relied
        // on. Capture a best-effort active-call number here so filenames and
        // the recording log can still include a number on ROMs where the call
        // log is delayed or sparse.
        captureActiveCallNumber()

        val session = HeadlessRecorderSession(
            outputDir = config.outputDir,
            minDurationSeconds = config.minDurationSeconds,
            direction = direction,
            stereoEnabled = config.stereoEnabled,
            listener = this,
        )
        recorder = session

        println("Starting recorder session with direction=${direction?.name ?: "unknown"}")
        notifier.showRecordingInProgress(config.outputDir, direction)
        statusWriter.update(
            mapOf(
                "recorder.state" to "recording",
                "recording.direction" to (direction?.name?.lowercase() ?: "unknown"),
                "last.error" to null,
            ),
        )

        session.start()
    }

    override fun onRecorderFinished(result: HeadlessRecorderSession.Result) {
        recorder = null
        notifier.dismissRecordingInProgress()

        val resolvedPhoneNumber = HeadlessCallLogResolver.resolveRecentNumber(
            context = context,
            direction = result.direction,
            startedAt = result.startedAt,
            fallbackRawNumber = lastKnownCallNumberRaw,
        )
        lastKnownCallNumberRaw = null

        val finalResult = maybeRenameOutputFile(result, resolvedPhoneNumber)

        if (config.logEnabled) {
            appendRecordingLog(finalResult, resolvedPhoneNumber)
        }

        println(
            "Recorder finished: status=${finalResult.status.serializedName}, " +
                "duration=${finalResult.durationSeconds}, error=${finalResult.error}",
        )
        statusWriter.update(
            mapOf(
                "recorder.state" to "idle",
                "recording.direction" to null,
                "last.result" to finalResult.status.serializedName,
                "last.output" to finalResult.outputFile?.absolutePath,
                "last.duration_secs" to finalResult.durationSeconds?.toString(),
                "last.audio_channels" to finalResult.channelCount?.toString(),
                "last.error" to finalResult.error,
                "last.event" to "recording-finished",
                "last.phone_number" to resolvedPhoneNumber?.display,
            ),
        )

        if (finalResult.status == HeadlessRecorderSession.Status.Succeeded) {
            finalResult.outputFile?.let(::maybeAutoQueueRecording)
        }

        when (finalResult.status) {
            HeadlessRecorderSession.Status.Succeeded -> {
                if (finalResult.outputFile != null) {
                    notifier.showRecordingSuccess(
                        config.outputDir,
                        finalResult.outputFile,
                        finalResult.durationSeconds,
                    )
                }
            }
            HeadlessRecorderSession.Status.Failed -> {
                notifier.showRecordingFailure(config.outputDir, finalResult.error)
            }
            HeadlessRecorderSession.Status.DiscardedTooShort -> {
                notifier.showRecordingDiscarded(config.outputDir, finalResult.durationSeconds)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class HeadlessCallStateCallback(
        private val handler: (Int) -> Unit,
    ) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            handler(state)
        }
    }

    private class HeadlessPhoneStateReceiver(
        private val handler: (Intent) -> Unit,
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                return
            }

            handler(intent)
        }
    }

    private class HeadlessPowerStateReceiver(
        private val handler: (Intent) -> Unit,
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            intent ?: return
            handler(intent)
        }
    }

    @Suppress("DEPRECATION")
    private class HeadlessLegacyCallStateListener(
        private val handler: (Int) -> Unit,
    ) : PhoneStateListener() {
        @Deprecated("PhoneStateListener is only used for pre-Android 12 compatibility")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            handler(state)
        }
    }

    private fun serializeCallState(state: Int): String = when (state) {
        TelephonyManager.CALL_STATE_IDLE -> "idle"
        TelephonyManager.CALL_STATE_RINGING -> "ringing"
        TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
        else -> "unknown($state)"
    }

    private data class PolledState(
        val state: Int,
        val source: String,
    )

    private fun maybeRenameOutputFile(
        result: HeadlessRecorderSession.Result,
        resolvedPhoneNumber: ResolvedPhoneNumber?,
    ): HeadlessRecorderSession.Result {
        val outputFile = result.outputFile ?: return result
        val phoneComponent = resolvedPhoneNumber?.filenameComponent ?: return result
        val extension = outputFile.extension
        val renamed = File(
            outputFile.parentFile,
            "${outputFile.nameWithoutExtension}_$phoneComponent.${extension}",
        )

        if (renamed == outputFile || renamed.exists()) {
            return result
        }

        return if (outputFile.renameTo(renamed)) {
            println("Renamed recording to include phone number: ${renamed.name}")
            result.copy(outputFile = renamed)
        } else {
            result
        }
    }

    private fun appendRecordingLog(
        result: HeadlessRecorderSession.Result,
        resolvedPhoneNumber: ResolvedPhoneNumber?,
    ) {
        val startedAt = result.startedAt
        val timestamp = startedAt?.toString() ?: java.time.ZonedDateTime.now().toString()
        val entryId = buildString {
            append(startedAt?.toInstant()?.toEpochMilli() ?: System.currentTimeMillis())
            append('-')
            append(result.status.serializedName)
        }

        recordingLogStore.append(
            RecordingLogEntry(
                id = entryId,
                timestamp = timestamp,
                direction = serializeDirection(result.direction),
                phoneNumber = resolvedPhoneNumber?.display,
                status = result.status.serializedName,
                durationSeconds = result.durationSeconds,
                audioChannels = result.channelCount,
                outputFile = result.outputFile?.absolutePath,
                error = result.error,
            ),
        )
    }

    private fun serializeDirection(direction: CallDirection?): String? = when (direction) {
        CallDirection.IN -> "incoming"
        CallDirection.OUT -> "outgoing"
        CallDirection.CONFERENCE -> "conference"
        null -> null
    }

    private fun captureActiveCallNumber() {
        if (!lastKnownCallNumberRaw.isNullOrBlank()) {
            return
        }

        HeadlessActiveCallNumberResolver.resolveCurrentNumber()?.let { number ->
            lastKnownCallNumberRaw = number
            println("Captured active call number via telecom shell snapshot")
        }
    }

    private fun registerPowerReceiver() {
        try {
            val receiver = HeadlessPowerStateReceiver(::onPowerStateChanged)
            powerReceiver = receiver
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }

            val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }

            statusWriter.update(mapOf("power.receiver_registered" to "1"))
            updateChargingState(sticky?.let(::isChargingIntent) ?: false, "battery_receiver")
        } catch (e: Exception) {
            statusWriter.update(
                mapOf(
                    "power.receiver_registered" to "0",
                    "power.receiver_error" to (e.localizedMessage ?: e.javaClass.simpleName),
                ),
            )
            println(
                "Power-state receiver unavailable on this ROM: " +
                    (e.localizedMessage ?: e.javaClass.simpleName),
            )
        }
    }

    private fun onPowerStateChanged(intent: Intent) {
        updateChargingState(
            isCharging = isChargingIntent(intent),
            source = intent.action ?: "battery_changed",
        )
    }

    private fun isChargingIntent(intent: Intent): Boolean {
        return when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> true
            Intent.ACTION_POWER_DISCONNECTED -> false
            else -> {
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL ||
                    plugged != 0
            }
        }
    }

    private fun updateChargingState(isCharging: Boolean, source: String) {
        val changed = this.isCharging != isCharging
        this.isCharging = isCharging

        statusWriter.update(
            mapOf(
                "power.charging" to if (isCharging) "1" else "0",
                "power.last_source" to source,
            ),
        )

        val settings = loadAutomationSettings()
        if (!settings.shouldAutoQueue || !settings.requiresCharging) {
            return
        }

        if (isCharging) {
            maybeScheduleAutoQueueStart(settings, source)
            return
        }

        cancelAutoQueueStart()
        if (changed && autoQueueWorkerOwned) {
            deferAutoQueueWorker()
        }
    }

    private fun maybeAutoQueueRecording(recording: File) {
        val settings = loadAutomationSettings()
        if (!settings.shouldAutoQueue) {
            return
        }

        val result = HeadlessTranscriber.enqueueRecordings(
            moduleDir = config.moduleDir,
            transcriptDir = settings.transcriptDir,
            language = settings.language,
            formatName = settings.format,
            conflictPolicyName = "skip",
            speakerSelfName = settings.speakerSelfName,
            recordings = listOf(recording),
        )

        if (result.queued.isEmpty()) {
            return
        }

        println("Auto-queued recording for transcription: ${recording.name}")
        if (settings.requiresCharging) {
            maybeScheduleAutoQueueStart(settings, "recording_finished")
        } else {
            startAutoQueueWorkerIfNeeded(settings, "recording_finished")
        }
    }

    private fun loadAutomationSettings(): TranscriberAutomationSettings {
        val outputDir = configStore.getOrDefault("output.dir", config.outputDir.absolutePath)
        return TranscriberAutomationSettings(
            transcriberEnabled = configStore.getBoolean("transcriber.enabled", false),
            autoQueueEnabled = configStore.getBoolean("transcriber.auto_queue", false),
            requiresCharging = configStore.getBoolean("transcriber.auto_queue_require_charging", false),
            chargeDelaySeconds = configStore.getInt("transcriber.auto_queue_charge_delay_seconds", 30).coerceAtLeast(0),
            transcriptDir = File(
                configStore.getOrDefault(
                    "transcriber.output_dir",
                    "$outputDir/transcripts",
                ),
            ),
            language = configStore.getOrDefault("transcriber.language", "en"),
            format = configStore.getOrDefault("transcriber.output_format", "txt"),
            speakerSelfName = configStore.getOrDefault("transcriber.speaker_self_name", "Speaker A"),
        )
    }

    private fun maybeScheduleAutoQueueStart(settings: TranscriberAutomationSettings, source: String) {
        cancelAutoQueueStart()
        if (!settings.shouldAutoQueue || !settings.requiresCharging || !isCharging) {
            return
        }
        if (!HeadlessTranscriber.hasPendingJobs(config.moduleDir)) {
            return
        }

        val generation = synchronized(this@HeadlessDaemon) {
            autoQueueStartGeneration += 1
            autoQueueStartGeneration
        }
        val delayMs = settings.chargeDelaySeconds * 1000L
        autoQueueStartThread = Thread(
            {
                try {
                    if (delayMs > 0L) {
                        Thread.sleep(delayMs)
                    }
                } catch (_: InterruptedException) {
                    return@Thread
                }

                val latestGeneration = synchronized(this@HeadlessDaemon) { autoQueueStartGeneration }
                if (generation != latestGeneration) {
                    return@Thread
                }

                val latestSettings = loadAutomationSettings()
                if (!latestSettings.shouldAutoQueue || !latestSettings.requiresCharging || !isCharging) {
                    return@Thread
                }
                if (!HeadlessTranscriber.hasPendingJobs(config.moduleDir)) {
                    return@Thread
                }

                println("Starting transcriber worker after charging delay from $source")
                startAutoQueueWorkerIfNeeded(latestSettings, source)
            },
            "HeadlessTranscriberAutoQueueStart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun cancelAutoQueueStart() {
        synchronized(this@HeadlessDaemon) {
            autoQueueStartGeneration += 1
        }
        autoQueueStartThread?.interrupt()
        autoQueueStartThread = null
    }

    private fun startAutoQueueWorkerIfNeeded(settings: TranscriberAutomationSettings, source: String) {
        if (!settings.shouldAutoQueue) {
            return
        }
        if (settings.requiresCharging && !isCharging) {
            return
        }
        if (!actionScript.isFile) {
            println("Unable to start transcriber worker automatically: missing ${actionScript.absolutePath}")
            return
        }
        if (!HeadlessTranscriber.hasPendingJobs(config.moduleDir)) {
            autoQueueWorkerOwned = false
            return
        }
        if (HeadlessTranscriber.hasRunningJobs(config.moduleDir)) {
            autoQueueWorkerOwned = false
            return
        }

        val result = ShellCommandRunner.run(
            "sh",
            actionScript.absolutePath,
            "transcriber",
            "start-worker",
            timeoutMs = 10_000,
        )
        if (result.exitCode == 0) {
            autoQueueWorkerOwned = settings.requiresCharging
            println("Started transcriber worker automatically via $source")
        } else {
            autoQueueWorkerOwned = false
            println(
                "Automatic transcriber worker start failed: " +
                    result.stderr.ifBlank { result.stdout }.ifBlank { "unknown error" },
            )
        }
    }

    private fun deferAutoQueueWorker() {
        if (!actionScript.isFile) {
            autoQueueWorkerOwned = false
            return
        }

        val result = ShellCommandRunner.run(
            "sh",
            actionScript.absolutePath,
            "transcriber",
            "defer",
            timeoutMs = 10_000,
        )
        if (result.exitCode == 0) {
            println("Deferred transcriber worker until charging resumes")
        } else {
            println(
                "Unable to defer transcriber worker cleanly: " +
                    result.stderr.ifBlank { result.stdout }.ifBlank { "unknown error" },
            )
        }
        autoQueueWorkerOwned = false
    }

    private data class TranscriberAutomationSettings(
        val transcriberEnabled: Boolean,
        val autoQueueEnabled: Boolean,
        val requiresCharging: Boolean,
        val chargeDelaySeconds: Int,
        val transcriptDir: File,
        val language: String,
        val format: String,
        val speakerSelfName: String,
    ) {
        val shouldAutoQueue: Boolean
            get() = transcriberEnabled && autoQueueEnabled
    }

    companion object {
        private const val POLL_INTERVAL_MS = 500L
    }
}

private class StatusWriter(
    private val file: File,
) {
    @Synchronized
    fun update(overrides: Map<String, String?>) {
        val merged = load()

        for ((key, value) in overrides) {
            if (value == null) {
                merged.remove(key)
            } else {
                merged[key] = sanitize(value)
            }
        }

        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(
            merged.entries
                .sortedBy { it.key }
                .joinToString(separator = "\n", postfix = "\n") { "${it.key}=${it.value}" },
        )
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
    }

    private fun load(): LinkedHashMap<String, String> {
        val values = LinkedHashMap<String, String>()
        if (!file.exists()) {
            return values
        }

        for (line in file.readLines()) {
            val separator = line.indexOf('=')
            if (separator <= 0) {
                continue
            }

            values[line.substring(0, separator)] = line.substring(separator + 1)
        }

        return values
    }

    private fun sanitize(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ')
}
