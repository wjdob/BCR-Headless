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
import com.chiller3.bcr.format.WaveFormat
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
            outputFile = File(outputDir, "$stem.wav")
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
                ),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordUntilStop(fd: java.io.FileDescriptor): RecordingInfo {
        AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE.toInt(),
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBufferSize >= 0) {
            "Failure when querying minimum buffer size: $minBufferSize"
        }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_CALL,
            SAMPLE_RATE.toInt(),
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 6,
        )
        require(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize: state=${audioRecord.state}"
        }

        try {
            audioRecord.startRecording()
            require(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord failed to enter recording state: state=${audioRecord.recordingState}"
            }
            println("AudioRecord started with VOICE_CALL source and buffer=${minBufferSize * 6}")

            val container = WaveFormat.getContainer(fd)
            try {
                val mediaFormat = WaveFormat.getMediaFormat(1, SAMPLE_RATE, null)
                val encoder = WaveFormat.getEncoder(mediaFormat, container)

                try {
                    encoder.start()
                    return encodeLoop(audioRecord, encoder, minBufferSize)
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

    private fun encodeLoop(
        audioRecord: AudioRecord,
        encoder: Encoder,
        bufferSize: Int,
    ): RecordingInfo {
        var numFramesTotal = 0L
        var numFramesEncoded = 0L
        var wasPureSilence = true

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

            interleaveMono(inputBuffer, outputBuffer)
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
            numFramesTotal += writtenBytes / BYTES_PER_SAMPLE
            numFramesEncoded += writtenBytes / BYTES_PER_SAMPLE

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
            sampleRate = SAMPLE_RATE.toInt(),
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
        const val SAMPLE_RATE: UInt = 16_000u

        private const val BYTES_PER_SAMPLE = 2
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        private fun interleaveMono(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer) {
            val bytesToCopy = min(inputBuffer.remaining(), outputBuffer.remaining())
            val framesToCopy = bytesToCopy / BYTES_PER_SAMPLE

            repeat(framesToCopy) {
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
    ) {
        val durationSecsWall: Double
            get() = wallDurationNanos / 1_000_000_000.0
        val durationSecsEncoded: Double
            get() = framesEncoded.toDouble() / sampleRate
        val durationWall: Duration
            get() = Duration.ofNanos(wallDurationNanos)
    }
}
