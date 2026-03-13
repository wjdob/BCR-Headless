/*
 * SPDX-FileCopyrightText: 2026 wjdob
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:Suppress("DEPRECATION")

package com.chiller3.bcr.headless

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import java.io.File

object HeadlessMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) {
            "Expected subcommand: daemon <module_dir> <output_dir> <min_duration> <log_enabled> <notifications_enabled> | probe <module_dir> <output_dir> | open-output-dir <output_dir> | open-recording <path>"
        }

        when (args[0]) {
            "daemon" -> runDaemon(args)
            "probe" -> runProbe(args)
            "open-output-dir" -> runOpenOutputDir(args)
            "open-recording" -> runOpenRecording(args)
            else -> throw IllegalArgumentException("Unknown subcommand: ${args[0]}")
        }
    }

    private fun runDaemon(args: Array<String>) {
        require(args.size >= 4) {
            "Usage: daemon <module_dir> <output_dir> <min_duration> <log_enabled> <notifications_enabled>"
        }

        val moduleDir = File(args[1])
        val outputDir = File(args[2])
        val minDurationSeconds = args[3].toIntOrNull() ?: 0
        // Shell scripts pass booleans as 1/0, so accept both human-readable
        // and shell-style forms to keep WebUI toggles and daemon behavior in
        // sync.
        val logEnabled = parseBooleanArg(args.getOrNull(4), defaultValue = true)
        val notificationsEnabled = parseBooleanArg(args.getOrNull(5), defaultValue = true)
        val context = getSystemContext()

        HeadlessDaemon(
            context = context,
            config = HeadlessConfig(
                moduleDir = moduleDir,
                outputDir = outputDir,
                minDurationSeconds = minDurationSeconds,
                logEnabled = logEnabled,
                notificationsEnabled = notificationsEnabled,
            ),
        ).start()

        Looper.loop()
    }

    private fun runProbe(args: Array<String>) {
        require(args.size >= 3) {
            "Usage: probe <module_dir> <output_dir>"
        }

        val moduleDir = File(args[1])
        val outputDir = File(args[2])
        val context = getSystemContext()
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        val telecomManager = context.getSystemService(TelecomManager::class.java)
        val minBuffer = AudioRecord.getMinBufferSize(
            HeadlessRecorderSession.SAMPLE_RATE.toInt(),
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

        println("module.dir=${moduleDir.absolutePath}")
        println("output.dir=${outputDir.absolutePath}")
        println("output.dir.exists=${outputDir.exists()}")
        println("output.dir.parent.exists=${outputDir.parentFile?.exists() == true}")
        println("sdk.int=${Build.VERSION.SDK_INT}")
        println("system.context=${context.packageName}")
        println("telephony.present=${telephonyManager != null}")
        println("call.state=${telephonyManager?.callState ?: -1}")
        println("telecom.present=${telecomManager != null}")
        println("telecom.in_call=${try { telecomManager?.isInCall == true } catch (_: Exception) { false }}")
        println("voice_call.min_buffer=${minBuffer}")
    }

    private fun runOpenOutputDir(args: Array<String>) {
        require(args.size >= 2) {
            "Usage: open-output-dir <output_dir>"
        }

        val outputDir = File(args[1])
        val target = HeadlessIntents.createOpenOutputDirTarget(outputDir)
        try {
            // Delegate the launch to the shell's `am start` path instead of the
            // synthetic helper context. Some ROMs reject activity launches from
            // the root-side helper UID even when normal shell launches work.
            val result = ShellCommandRunner.run(
                "am",
                "start",
                "--grant-read-uri-permission",
                "-a",
                Intent.ACTION_VIEW,
                "-d",
                target.uri.toString(),
                "-t",
                target.mimeType,
            )

            if (result.exitCode == 0) {
                println("opened.output_dir=${outputDir.absolutePath}")
            } else {
                println(
                    "open_output_dir.unavailable=" +
                        result.stderr.ifBlank { result.stdout }.ifBlank { "am start failed" },
                )
            }
        } catch (e: Exception) {
            println("open_output_dir.unavailable=${e.localizedMessage ?: e.javaClass.simpleName}")
            println("open_output_dir.path=${outputDir.absolutePath}")
        }
    }

    private fun runOpenRecording(args: Array<String>) {
        require(args.size >= 2) {
            "Usage: open-recording <path>"
        }

        val file = File(args[1])
        if (!file.exists()) {
            println("open_recording.missing=${file.absolutePath}")
            return
        }

        val target = HeadlessIntents.createOpenRecordingTarget(file)

        try {
            val result = ShellCommandRunner.run(
                "am",
                "start",
                "--grant-read-uri-permission",
                "-a",
                Intent.ACTION_VIEW,
                "-d",
                target.uri.toString(),
                "-t",
                target.mimeType,
            )

            if (result.exitCode == 0) {
                println("opened.recording=${file.absolutePath}")
            } else {
                println(
                    "open_recording.unavailable=" +
                        result.stderr.ifBlank { result.stdout }.ifBlank { "am start failed" },
                )
            }
        } catch (e: Exception) {
            println("open_recording.unavailable=${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    private fun getSystemContext(): Context {
        if (Looper.getMainLooper() == null) {
            // app_process entrypoints do not have an application lifecycle, so
            // bootstrap a main looper explicitly before touching framework
            // services that expect one.
            Looper.prepareMainLooper()
        }

        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val systemMain = activityThreadClass.getDeclaredMethod("systemMain")
        val activityThread = systemMain.invoke(null)
        val getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext")

        return getSystemContext.invoke(activityThread) as Context
    }

    private fun parseBooleanArg(
        value: String?,
        defaultValue: Boolean,
    ): Boolean = when (value?.trim()?.lowercase()) {
        null, "" -> defaultValue
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> defaultValue
    }
}
