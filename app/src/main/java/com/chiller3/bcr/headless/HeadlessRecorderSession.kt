/*
 * SPDX-FileCopyrightText: 2026 wjdob
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.headless

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.system.Os
import com.chiller3.bcr.format.Encoder
import com.chiller3.bcr.output.CallDirection
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min
import android.os.Process as AndroidProcess

class HeadlessRecorderSession(
    private val outputDir: File,
    private val minDurationSeconds: Int,
    private val direction: CallDirection?,
    private val stereoEnabled: Boolean,
    private val recordingFormat: HeadlessRecordingFormat,
    private val listener: Listener,
) : Thread(HeadlessRecorderSession::class.java.simpleName) {
    interface Listener {
        fun onRecorderFinished(result: Result)
    }

    enum class Status(val serializedName: String) {
        Succeeded("succeeded"),
        DiscardedTooShort("discarded_too_short"),
        Failed("failed"),
    }

    data class Result(
        val status: Status,
        val outputFile: File?,
        val durationSeconds: Double?,
        val error: String?,
        val startedAt: ZonedDateTime?,
        val direction: CallDirection?,
        val channelCount: Int?,
    )

    private var stopRequested = false

    fun requestStop() {
        stopRequested = true
    }

    override fun run() {
        var outputFile: File? = null
        var startedAt: ZonedDateTime? = null

        try {
            outputDir.mkdirs()

            startedAt = ZonedDateTime.now()
            val stem = buildFileStem(startedAt)
            outputFile = File(outputDir, "$stem.${recordingFormat.extension}")
            println("Recorder session started: output=${outputFile.absolutePath}")

            val info = FileOutputStream(outputFile).use { stream ->
                val recordingInfo = recordUntilStop(stream.fd)
                Os.fsync(stream.fd)
                recordingInfo
            }

            if (info.durationSecsEncoded < minDurationSeconds) {
                println(
                    "Discarding recording because duration ${info.durationSecsEncoded}s " +
                        "is below minimum ${minDurationSeconds}s",
                )
                outputFile.delete()
                listener.onRecorderFinished(
                    Result(
                        status = Status.DiscardedTooShort,
                        outputFile = null,
                        durationSeconds = info.durationSecsEncoded,
                        error = null,
                        startedAt = startedAt,
                        direction = direction,
                        channelCount = info.channelCount,
                    ),
                )
                return
            }

            println(
                "Recorder session succeeded: duration=${info.durationSecsEncoded}s",
            )
            listener.onRecorderFinished(
                Result(
                    status = Status.Succeeded,
                    outputFile = outputFile,
                    durationSeconds = info.durationSecsEncoded,
                    error = null,
                    startedAt = startedAt,
                    direction = direction,
                    channelCount = info.channelCount,
                ),
            )
        } catch (e: Exception) {
            System.err.println("Recorder session failed")
            e.printStackTrace()
            outputFile?.delete()

            listener.onRecorderFinished(
                Result(
                    status = Status.Failed,
                    outputFile = null,
                    durationSeconds = null,
                    error = e.localizedMessage ?: e.javaClass.simpleName,
                    startedAt = startedAt,
                    direction = direction,
                    channelCount = null,
                ),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordUntilStop(fd: java.io.FileDescriptor): RecordingInfo {
        AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)

        val encoderFormat = recordingFormat.newFormat()
        val sampleRate = encoderFormat.sampleRateInfo.default
        val recordingInput = createAudioRecord(stereoEnabled, sampleRate)
        val audioRecord = recordingInput.audioRecord

        try {
            audioRecord.startRecording()
            require(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord failed to enter recording state: state=${audioRecord.recordingState}"
            }
            println(
                "AudioRecord started with VOICE_CALL source, channels=${recordingInput.channelCount}, " +
                    "buffer=${recordingInput.minBufferSize * 6}",
            )

            val container = encoderFormat.getContainer(fd)
            try {
                val mediaFormat = encoderFormat.getMediaFormat(recordingInput.channelCount, sampleRate, null)
                val encoder = encoderFormat.getEncoder(mediaFormat, container)

                try {
                    encoder.start()
                    return encodeLoop(
                        audioRecord = audioRecord,
                        encoder = encoder,
                        bufferSize = recordingInput.minBufferSize,
                        channelCount = recordingInput.channelCount,
                        sampleRate = sampleRate.toInt(),
                    )
                } finally {
                    encoder.stop()
                    encoder.release()
                }
            } finally {
                container.release()
            }
        } finally {
            try {
                audioRecord.stop()
            } catch (_: Exception) {
                // Ignore stop failures when the recorder is already unwinding.
            }

            audioRecord.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(preferStereo: Boolean, sampleRate: UInt): RecordingInput {
        return openVoiceCallInput(preferStereo, sampleRate, logger = ::println)
    }

    private fun encodeLoop(
        audioRecord: AudioRecord,
        encoder: Encoder,
        bufferSize: Int,
        channelCount: Int,
        sampleRate: Int,
    ): RecordingInfo {
        var numFramesTotal = 0L
        var numFramesEncoded = 0L
        var wasPureSilence = true

        val frameSize = BYTES_PER_SAMPLE * channelCount
        val baseSize = bufferSize * 2
        val inputBuffer = ByteBuffer.allocateDirect(baseSize)
        val outputBuffer = ByteBuffer.allocate(baseSize)
        val wallBeginNanos = System.nanoTime()

        while (!stopRequested) {
            val oldPos = inputBuffer.position()
            val unconsumed = inputBuffer.slice()

            val bytesRead = audioRecord.read(
                unconsumed,
                unconsumed.remaining(),
                AudioRecord.READ_NON_BLOCKING,
            )
            if (bytesRead < 0) {
                throw IllegalStateException("Failed to read from VOICE_CALL source: $bytesRead")
            }

            inputBuffer.position(0)
            inputBuffer.limit(oldPos + bytesRead)

            copyPcm(inputBuffer, outputBuffer, frameSize)
            val writtenBytes = outputBuffer.limit()

            if (wasPureSilence) {
                for (i in 0 until writtenBytes / BYTES_PER_SAMPLE) {
                    if (outputBuffer.getShort(i * BYTES_PER_SAMPLE) != 0.toShort()) {
                        wasPureSilence = false
                        break
                    }
                }
            }

            encoder.encode(outputBuffer, false)
            numFramesTotal += writtenBytes / frameSize
            numFramesEncoded += writtenBytes / frameSize

            inputBuffer.compact()
            outputBuffer.clear()

            if (bytesRead == 0 && !stopRequested) {
                sleep(20)
            }
        }

        if (wasPureSilence) {
            throw IllegalStateException("Audio contained pure silence")
        }

        outputBuffer.limit(0)
        encoder.encode(outputBuffer, true)

        return RecordingInfo(
            wallDurationNanos = System.nanoTime() - wallBeginNanos,
            framesTotal = numFramesTotal,
            framesEncoded = numFramesEncoded,
            sampleRate = sampleRate,
            channelCount = channelCount,
        )
    }

    private fun buildFileStem(timestamp: ZonedDateTime): String {
        val suffix = when (direction) {
            CallDirection.IN -> "in"
            CallDirection.OUT -> "out"
            CallDirection.CONFERENCE -> "conference"
            null -> "call"
        }

        return "${TIMESTAMP_FORMAT.format(timestamp)}_${suffix}"
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE: UInt = 16_000u

        private const val BYTES_PER_SAMPLE = 2
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        @SuppressLint("MissingPermission")
        fun probeVoiceCallCapability(preferStereo: Boolean = true): VoiceCallCapability {
            val input = openVoiceCallInput(preferStereo, DEFAULT_SAMPLE_RATE, logger = null)
            return try {
                val stereoSupported = input.channelCount >= 2
                VoiceCallCapability(
                    stereoSupported = stereoSupported,
                    detectedMode = if (stereoSupported) "stereo" else "mono",
                    status = if (stereoSupported) "stereo_available" else "mono_fallback_only",
                    note = if (stereoSupported) {
                        "Stereo VOICE_CALL initialization succeeded"
                    } else {
                        "Stereo VOICE_CALL initialization was unavailable; mono fallback is recommended"
                    },
                )
            } finally {
                try {
                    input.audioRecord.release()
                } catch (_: Exception) {
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun openVoiceCallInput(
            preferStereo: Boolean,
            sampleRate: UInt,
            logger: ((String) -> Unit)?,
        ): RecordingInput {
            val specs = buildList {
                if (preferStereo) {
                    add(ChannelSpec(AudioFormat.CHANNEL_IN_STEREO, 2, "stereo"))
                }
                add(ChannelSpec(AudioFormat.CHANNEL_IN_MONO, 1, "mono"))
            }

            var lastError: String? = null

            for (spec in specs) {
                val minBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate.toInt(),
                    spec.channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBufferSize < 0) {
                    lastError = "minimum buffer query failed for ${spec.label}: $minBufferSize"
                    logger?.invoke("Skipping ${spec.label} VOICE_CALL input: $lastError")
                    continue
                }

                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_CALL,
                    sampleRate.toInt(),
                    spec.channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize * 6,
                )
                if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    if (preferStereo && spec.channelCount == 1) {
                        logger?.invoke("Stereo VOICE_CALL input unavailable; falling back to mono")
                    }
                    return RecordingInput(audioRecord, spec.channelCount, minBufferSize)
                }

                lastError = "AudioRecord failed to initialize ${spec.label}: state=${audioRecord.state}"
                audioRecord.release()
                logger?.invoke("Skipping ${spec.label} VOICE_CALL input: $lastError")
            }

            throw IllegalStateException(lastError ?: "No VOICE_CALL input format is available")
        }

        private fun copyPcm(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, frameSize: Int) {
            val bytesToCopy = min(inputBuffer.remaining(), outputBuffer.remaining())
            val alignedBytesToCopy = bytesToCopy - (bytesToCopy % frameSize)
            val samplesToCopy = alignedBytesToCopy / BYTES_PER_SAMPLE

            repeat(samplesToCopy) {
                outputBuffer.putShort(inputBuffer.getShort())
            }

            outputBuffer.flip()
        }
    }

    private data class RecordingInfo(
        val wallDurationNanos: Long,
        val framesTotal: Long,
        val framesEncoded: Long,
        val sampleRate: Int,
        val channelCount: Int,
    ) {
        val durationSecsWall: Double
            get() = wallDurationNanos / 1_000_000_000.0
        val durationSecsEncoded: Double
            get() = framesEncoded.toDouble() / sampleRate
        val durationWall: Duration
            get() = Duration.ofNanos(wallDurationNanos)
    }

    private data class RecordingInput(
        val audioRecord: AudioRecord,
        val channelCount: Int,
        val minBufferSize: Int,
    )

    private data class ChannelSpec(
        val channelMask: Int,
        val channelCount: Int,
        val label: String,
    )

    data class VoiceCallCapability(
        val stereoSupported: Boolean,
        val detectedMode: String,
        val status: String,
        val note: String,
    )
}
