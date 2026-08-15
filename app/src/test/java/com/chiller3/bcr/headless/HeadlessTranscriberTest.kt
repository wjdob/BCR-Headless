/*
 * SPDX-FileCopyrightText: 2026 wjdob
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.headless

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlessTranscriberTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun temporaryDirectory(): File = Files.createTempDirectory("bcr-headless-test").toFile()

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val output = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(output, true, Charsets.UTF_8))
            block()
        } finally {
            System.setOut(original)
        }
        return output.toString(Charsets.UTF_8)
    }

    @Test
    fun enqueueStoresBothSpeakerNamesAndDoesNotStartWork() {
        val root = temporaryDirectory()
        val transcriptDir = File(root, "transcripts")
        val recording = File(root, "incoming.wav").apply { writeBytes(byteArrayOf()) }

        val result = HeadlessTranscriber.enqueueRecordings(
            moduleDir = root,
            transcriptDir = transcriptDir,
            language = "en",
            formatName = "srt",
            conflictPolicyName = "skip",
            speakerSelfName = "Me",
            speakerRemoteName = "Caller",
            recordings = listOf(recording),
        )

        assertEquals(1, result.queued.size)
        assertEquals("Me", result.queued.single().speakerSelfName)
        assertEquals("Caller", result.queued.single().speakerRemoteName)
        assertEquals("srt", result.queued.single().format)
        assertEquals("queued", result.queued.single().status)
        assertFalse(File(root, ".state/transcriber.pid").exists())
    }

    @Test
    fun queueCanBeReorderedAndFailedJobRetried() {
        val root = temporaryDirectory()
        val transcriptDir = File(root, "transcripts")
        val recordings = (1..3).map { index -> File(root, "$index.wav").apply { writeBytes(byteArrayOf()) } }
        val result = HeadlessTranscriber.enqueueRecordings(
            root, transcriptDir, "en", "txt", "skip", "Speaker A", "Speaker B", recordings,
        )
        val firstId = result.queued[0].id

        captureStdout {
            HeadlessTranscriber.run(arrayOf("control", root.absolutePath, "move-down", firstId))
        }
        val queueFile = File(root, ".state/transcriber-queue.json")
        var queue = json.decodeFromString<TranscriptionQueue>(queueFile.readText())
        assertEquals(firstId, queue.jobs[1].id)

        queue = queue.copy(jobs = queue.jobs.mapIndexed { index, job ->
            if (index == 1) job.copy(status = "failed", progress = 61, stage = "failed", error = "test") else job
        })
        queueFile.writeText(json.encodeToString(TranscriptionQueue.serializer(), queue))
        captureStdout {
            HeadlessTranscriber.run(arrayOf("control", root.absolutePath, "retry", firstId))
        }
        val retried = json.decodeFromString<TranscriptionQueue>(queueFile.readText()).jobs.first { it.id == firstId }
        assertEquals("queued", retried.status)
        assertEquals(0, retried.progress)
        assertEquals(null, retried.error)
    }

    @Test
    fun staleRunningJobIsRecoveredBeforeWorkerRespectsPause() {
        val root = temporaryDirectory()
        val recording = File(root, "call.wav").apply { writeBytes(byteArrayOf()) }
        val queued = HeadlessTranscriber.enqueueRecordings(
            root, File(root, "transcripts"), "en", "txt", "skip", "Speaker A", "Speaker B", listOf(recording),
        ).queued.single()
        val queueFile = File(root, ".state/transcriber-queue.json")
        queueFile.writeText(
            json.encodeToString(
                TranscriptionQueue.serializer(),
                TranscriptionQueue(listOf(queued.copy(status = "running", progress = 42, stage = "transcribing"))),
            ),
        )
        File(root, ".state/transcriber.pause").writeText("1\n")

        HeadlessTranscriber.run(
            arrayOf("worker", root.absolutePath, "/missing/cli", "/missing/base", "/missing/tdrz", "0"),
        )

        val recovered = json.decodeFromString<TranscriptionQueue>(queueFile.readText()).jobs.single()
        assertEquals("queued", recovered.status)
        assertEquals("queued", recovered.stage)
        assertEquals(0, recovered.progress)
    }

    @Test
    fun libraryPagingFiltersAndSortsWithoutReturningEveryFile() {
        val root = temporaryDirectory()
        val outputDir = File(root, "recordings").apply { mkdirs() }
        val transcriptDir = File(outputDir, "transcripts").apply { mkdirs() }
        (1..5).forEach { index ->
            File(outputDir, "call-$index.wav").apply {
                writeBytes(byteArrayOf())
                setLastModified(1_000L * index)
            }
        }
        File(transcriptDir, "call-3.txt").writeText("done")

        val raw = captureStdout {
            HeadlessTranscriber.run(
                arrayOf(
                    "library", root.absolutePath, outputDir.absolutePath, transcriptDir.absolutePath,
                    "txt", "1", "2", "call", "all", "oldest", "all",
                ),
            )
        }
        val page = json.decodeFromString<RecordingCandidatePage>(raw)
        assertEquals(5, page.total)
        assertEquals(1, page.offset)
        assertEquals(listOf("call-2.wav", "call-3.wav"), page.items.map { it.name })
        assertTrue(page.hasMore)
        assertTrue(page.items.last().selectedTranscriptExists)
    }

    @Test
    fun subtitleExportsKeepTimestampsAndSpeakerOrder() {
        val utterances = listOf(
            TranscriptUtterance(1_200, 3_500, "Speaker A", "Hello."),
            TranscriptUtterance(3_800, 5_900, "Speaker B", "Hi."),
        )
        val metadata = listOf(
            "Recording: call.wav",
            "Diarization: stereo",
            "Speakers: Speaker A, Speaker B",
        )

        val srt = HeadlessTranscriber.formatSrt(utterances, metadata)
        assertTrue(srt.contains("00:00:00,000 --> 00:00:00,001"))
        assertTrue(srt.contains("Recording: call.wav"))
        assertTrue(srt.contains("00:00:01,200 --> 00:00:03,500"))
        assertTrue(srt.indexOf("Speaker A: Hello.") < srt.indexOf("Speaker B: Hi."))

        val vtt = HeadlessTranscriber.formatVtt(utterances, metadata)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("NOTE\nRecording: call.wav"))
        assertTrue(vtt.contains("00:00:03.800 --> 00:00:05.900"))
    }

    @Test
    fun everyTranscriptFormatUsesTheRecordingStem() {
        val root = temporaryDirectory()
        val recording = File(root, "call:sample.wav")
        val expected = mapOf(
            TranscriptFormat.Txt to "call_sample.txt",
            TranscriptFormat.Docx to "call_sample.docx",
            TranscriptFormat.Srt to "call_sample.srt",
            TranscriptFormat.Vtt to "call_sample.vtt",
            TranscriptFormat.Json to "call_sample.json",
        )
        for ((format, name) in expected) {
            assertEquals(name, HeadlessTranscriber.transcriptPath(root, recording, format).name)
        }
    }

    @Test
    fun jsonExportContainsSourceDiarizationSpeakersAndTimestamps() {
        val raw = HeadlessTranscriber.formatJsonTranscript(
            sourceName = "call.wav",
            sourceFile = "/recordings/call.wav",
            sourceSizeBytes = 4096,
            generatedAt = "2026-08-14T12:00:00Z",
            language = "en",
            diarizationMode = "stereo",
            speakerSelfName = "Me",
            speakerRemoteName = "Caller",
            utterances = listOf(TranscriptUtterance(1_000, 2_000, "Caller", "Hello.")),
            transcriptText = "[00:00:01.000] Caller: Hello.",
        )
        val root = json.parseToJsonElement(raw).jsonObject
        assertEquals("call.wav", root.getValue("sourceName").jsonPrimitive.content)
        assertEquals("stereo", root.getValue("diarizationMode").jsonPrimitive.content)
        assertEquals("Me", root.getValue("speakerSelfName").jsonPrimitive.content)
        assertEquals("Caller", root.getValue("speakerRemoteName").jsonPrimitive.content)
        assertEquals(
            "1000",
            root.getValue("utterances").jsonArray.single().jsonObject.getValue("startMs").jsonPrimitive.content,
        )
    }

    @Test
    fun oldQueueJsonReceivesBackwardCompatibleDefaults() {
        val oldJson = """{
            "jobs": [{
                "id": "legacy", "recordingPath": "/a.wav", "transcriptPath": "/a.txt",
                "language": "en", "speakerSelfName": "Speaker A", "format": "txt",
                "overwrite": false, "status": "queued", "progress": 0,
                "createdAt": "2026-01-01T00:00:00Z"
            }]
        }"""
        val job = json.decodeFromString<TranscriptionQueue>(oldJson).jobs.single()
        assertEquals("Speaker B", job.speakerRemoteName)
        assertEquals(null, job.stage)
    }

    @Test
    fun atomicReplacementNeverLeavesPartialFinalContent() {
        val root = temporaryDirectory()
        val source = File(root, ".transcript.tmp").apply { writeText("complete transcript") }
        val target = File(root, "transcript.txt").apply { writeText("old transcript") }

        HeadlessTranscriber.replaceFileAtomically(source, target)

        assertEquals("complete transcript", target.readText())
        assertFalse(source.exists())
    }

    @Test
    fun failedAtomicReplacementKeepsExistingFinalContent() {
        val root = temporaryDirectory()
        val missingSource = File(root, ".missing.tmp")
        val target = File(root, "transcript.txt").apply { writeText("valid transcript") }

        assertTrue(runCatching { HeadlessTranscriber.replaceFileAtomically(missingSource, target) }.isFailure)
        assertEquals("valid transcript", target.readText())
    }
}
