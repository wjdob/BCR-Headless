/*
 * SPDX-FileCopyrightText: 2026 wjdob
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.headless

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

object HeadlessTranscriber {
    private val JSON = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun run(args: Array<String>) {
        require(args.isNotEmpty()) {
            "Usage: transcriber [status|list|enqueue|worker|control|open-transcript] ..."
        }

        when (args[0]) {
            "status" -> runStatus(args)
            "list" -> runList(args)
            "enqueue" -> runEnqueue(args)
            "worker" -> runWorker(args)
            "control" -> runControl(args)
            "open-transcript" -> runOpenTranscript(args)
            else -> throw IllegalArgumentException("Unknown transcriber subcommand: ${args[0]}")
        }
    }

    private fun runStatus(args: Array<String>) {
        require(args.size >= 7) {
            "Usage: transcriber status <module_dir> <output_dir> <transcript_dir> <whisper_path> <model_path> <tinydiarize_model_path>"
        }

        val moduleDir = File(args[1])
        val outputDir = File(args[2])
        val transcriptDir = File(args[3])
        val whisperPath = File(args[4])
        val modelPath = File(args[5])
        val tinydiarizeModelPath = File(args[6])
        val state = TranscriberState(moduleDir)

        state.ensure()

        println(
            JSON.encodeToString(
                TranscriberStatus(
                    dependencies = dependencyStatus(whisperPath, modelPath, tinydiarizeModelPath),
                    outputDir = outputDir.absolutePath,
                    transcriptDir = transcriptDir.absolutePath,
                    queue = state.queue.load(),
                    runtime = state.runtime.load(),
                    paused = state.pauseFile.exists(),
                    stopRequested = state.stopFile.exists(),
                ),
            ),
        )
    }

    private fun runList(args: Array<String>) {
        require(args.size >= 5) {
            "Usage: transcriber list <module_dir> <output_dir> <transcript_dir> <format>"
        }

        val moduleDir = File(args[1])
        val outputDir = File(args[2])
        val transcriptDir = File(args[3])
        val format = TranscriptFormat.from(args[4])
        val state = TranscriberState(moduleDir)

        state.ensure()
        transcriptDir.mkdirs()

        val candidates = outputDir.walkTopDown()
            .onEnter { dir ->
                dir.canonicalFile != transcriptDir.canonicalFile
            }
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val txt = transcriptPath(transcriptDir, file, TranscriptFormat.Txt)
                val docx = transcriptPath(transcriptDir, file, TranscriptFormat.Docx)

                RecordingCandidate(
                    path = file.absolutePath,
                    name = file.name,
                    sizeBytes = file.length(),
                    modifiedAt = Instant.ofEpochMilli(file.lastModified()).toString(),
                    audioChannels = inspectWaveChannels(file),
                    transcriptTxtPath = txt.absolutePath,
                    transcriptDocxPath = docx.absolutePath,
                    transcriptTxtExists = txt.exists(),
                    transcriptDocxExists = docx.exists(),
                    selectedTranscriptExists = when (format) {
                        TranscriptFormat.Txt -> txt.exists()
                        TranscriptFormat.Docx -> docx.exists()
                    },
                )
            }
            .toList()

        println(JSON.encodeToString(candidates))
    }

    private fun runEnqueue(args: Array<String>) {
        require(args.size >= 7) {
            "Usage: transcriber enqueue <module_dir> <transcript_dir> <language> <format> <conflict_policy> <recording>..."
        }

        val moduleDir = File(args[1])
        val transcriptDir = File(args[2])
        val language = args[3].ifBlank { "en" }
        val format = TranscriptFormat.from(args[4])
        val conflictPolicy = ConflictPolicy.from(args[5])
        val recordings = args.drop(6).map(::File)
        val state = TranscriberState(moduleDir)

        state.ensure()
        transcriptDir.mkdirs()

        val conflicts = recordings.filter { transcriptPath(transcriptDir, it, format).exists() }
        if (conflictPolicy == ConflictPolicy.Cancel && conflicts.isNotEmpty()) {
            println(
                JSON.encodeToString(
                    EnqueueResult(
                        queued = emptyList(),
                        skipped = emptyList(),
                        conflicts = conflicts.map { it.absolutePath },
                        cancelled = true,
                    ),
                ),
            )
            return
        }

        val existingQueue = state.queue.load()
        val queuedPaths = existingQueue.jobs
            .filter { it.status in ACTIVE_STATUSES }
            .map { it.recordingPath }
            .toSet()
        val jobs = mutableListOf<TranscriptionJob>()
        val skipped = mutableListOf<String>()

        for (recording in recordings) {
            if (!recording.isFile || recording.extension.lowercase(Locale.ROOT) !in AUDIO_EXTENSIONS) {
                skipped += recording.absolutePath
                continue
            }

            val transcript = transcriptPath(transcriptDir, recording, format)
            if (transcript.exists() && conflictPolicy == ConflictPolicy.Skip) {
                skipped += recording.absolutePath
                continue
            }
            if (recording.absolutePath in queuedPaths) {
                skipped += recording.absolutePath
                continue
            }

            jobs += TranscriptionJob(
                id = "${Instant.now().toEpochMilli()}-${UUID.randomUUID().toString().take(8)}",
                recordingPath = recording.absolutePath,
                transcriptPath = transcript.absolutePath,
                language = language,
                format = format.serializedName,
                overwrite = conflictPolicy == ConflictPolicy.Overwrite,
                status = JobStatus.Queued.serializedName,
                createdAt = Instant.now().toString(),
                audioChannels = inspectWaveChannels(recording),
            )
        }

        if (jobs.isNotEmpty()) {
            state.queue.update { queue ->
                queue.copy(jobs = queue.jobs + jobs)
            }
        }

        println(
            JSON.encodeToString(
                EnqueueResult(
                    queued = jobs,
                    skipped = skipped,
                    conflicts = conflicts.map { it.absolutePath },
                    cancelled = false,
                ),
            ),
        )
    }

    private fun runWorker(args: Array<String>) {
        require(args.size >= 6) {
            "Usage: transcriber worker <module_dir> <whisper_path> <model_path> <tinydiarize_model_path> <notifications_enabled>"
        }

        val moduleDir = File(args[1])
        val whisperPath = File(args[2])
        val modelPath = File(args[3])
        val tinydiarizeModelPath = File(args[4])
        val notificationsEnabled = parseBoolean(args[5], default = true)
        val state = TranscriberState(moduleDir)
        val notifier = TranscriberNotifier(notificationsEnabled)

        state.ensure()
        state.stopFile.delete()

        while (true) {
            if (state.stopFile.exists()) {
                state.runtime.save(TranscriberRuntime(state = "stopped"))
                return
            }

            if (state.pauseFile.exists()) {
                state.runtime.save(TranscriberRuntime(state = "paused"))
                return
            }

            val job = state.queue.load().jobs.firstOrNull { it.status == JobStatus.Queued.serializedName }
            if (job == null) {
                state.runtime.save(TranscriberRuntime(state = "idle"))
                return
            }

            processJob(
                state = state,
                notifier = notifier,
                job = job,
                whisperPath = whisperPath,
                modelPath = modelPath,
                tinydiarizeModelPath = tinydiarizeModelPath,
            )
        }
    }

    private fun runControl(args: Array<String>) {
        require(args.size >= 3) {
            "Usage: transcriber control <module_dir> <pause|resume|stop|clear|remove> [job_id]"
        }

        val state = TranscriberState(File(args[1]))
        val command = args[2]
        state.ensure()

        when (command) {
            "pause" -> {
                state.pauseFile.writeText("1\n")
                state.runtime.update { it.copy(state = "paused") }
            }
            "resume" -> {
                state.pauseFile.delete()
                state.stopFile.delete()
                state.runtime.update { it.copy(state = "resuming") }
            }
            "stop" -> {
                state.stopFile.writeText("1\n")
                state.pauseFile.delete()
                state.queue.update { queue ->
                    queue.copy(
                        jobs = queue.jobs.map { job ->
                            if (job.status == JobStatus.Running.serializedName) {
                                job.copy(
                                    status = JobStatus.Cancelled.serializedName,
                                    error = "Stopped by user",
                                    completedAt = Instant.now().toString(),
                                )
                            } else {
                                job
                            }
                        },
                    )
                }
                state.runtime.update { it.copy(state = "stopping") }
            }
            "clear" -> {
                state.queue.update { queue ->
                    queue.copy(jobs = queue.jobs.filter { it.status in ACTIVE_STATUSES })
                }
            }
            "remove" -> {
                val id = args.getOrNull(3) ?: throw IllegalArgumentException("Missing job id")
                state.queue.update { queue ->
                    queue.copy(jobs = queue.jobs.filterNot { it.id == id && it.status != JobStatus.Running.serializedName })
                }
            }
            else -> throw IllegalArgumentException("Unknown transcriber control: $command")
        }

        println(JSON.encodeToString(state.queue.load()))
    }

    private fun runOpenTranscript(args: Array<String>) {
        require(args.size >= 2) {
            "Usage: transcriber open-transcript <path>"
        }

        val file = File(args[1])
        if (!file.exists()) {
            println("open_transcript.missing=${file.absolutePath}")
            return
        }

        val target = HeadlessIntents.createOpenTranscriptTarget(file)
        try {
            val result = ShellCommandRunner.run(
                "am",
                "start",
                "--grant-read-uri-permission",
                "-a",
                android.content.Intent.ACTION_VIEW,
                "-d",
                target.uri.toString(),
                "-t",
                target.mimeType,
            )

            if (result.exitCode == 0) {
                println("opened.transcript=${file.absolutePath}")
            } else {
                println(
                    "open_transcript.unavailable=" +
                        result.stderr.ifBlank { result.stdout }.ifBlank { "am start failed" },
                )
            }
        } catch (e: Exception) {
            println("open_transcript.unavailable=${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    private fun processJob(
        state: TranscriberState,
        notifier: TranscriberNotifier,
        job: TranscriptionJob,
        whisperPath: File,
        modelPath: File,
        tinydiarizeModelPath: File,
    ) {
        val startedAt = Instant.now()
        val recording = File(job.recordingPath)
        val transcript = File(job.transcriptPath)
        val channels = inspectWaveChannels(recording)

        fun fail(message: String) {
            state.queue.replace(job.id) {
                it.copy(
                    status = JobStatus.Failed.serializedName,
                    progress = 0,
                    error = message,
                    completedAt = Instant.now().toString(),
                    audioChannels = channels,
                )
            }
            state.runtime.save(
                TranscriberRuntime(
                    state = "failed",
                    activeJobId = job.id,
                    activeFile = recording.absolutePath,
                    error = message,
                ),
            )
            notifier.showFailure(recording, message)
        }

        if (!recording.isFile) {
            fail("Recording is missing")
            return
        }

        val diarization = try {
            diarizationMode(channels, tinydiarizeModelPath)
        } catch (e: Exception) {
            fail(e.localizedMessage ?: e.javaClass.simpleName)
            return
        }
        val chosenModel = when (diarization) {
            DiarizationMode.TinyDiarize -> tinydiarizeModelPath
            DiarizationMode.Stereo -> modelPath
        }

        if (!whisperPath.isFile) {
            fail("Missing whisper.cpp CLI: ${whisperPath.absolutePath}")
            return
        }
        if (!whisperPath.canExecute()) {
            whisperPath.setExecutable(true)
            if (!whisperPath.canExecute()) {
                fail("whisper.cpp CLI is not executable: ${whisperPath.absolutePath}")
                return
            }
        }
        if (!chosenModel.isFile) {
            fail("Missing Whisper model: ${chosenModel.absolutePath}")
            return
        }
        if (transcript.exists() && !job.overwrite) {
            state.queue.replace(job.id) {
                it.copy(
                    status = JobStatus.Skipped.serializedName,
                    progress = 100,
                    error = "Transcript already exists",
                    completedAt = Instant.now().toString(),
                    audioChannels = channels,
                    diarizationMode = diarization.serializedName,
                )
            }
            return
        }

        state.queue.replace(job.id) {
            it.copy(
                status = JobStatus.Running.serializedName,
                progress = 0,
                startedAt = startedAt.toString(),
                error = null,
                audioChannels = channels,
                diarizationMode = diarization.serializedName,
            )
        }
        state.runtime.save(
            TranscriberRuntime(
                state = "running",
                activeJobId = job.id,
                activeFile = recording.absolutePath,
                progress = 0,
                diarizationMode = diarization.serializedName,
            ),
        )
        notifier.showStarted(recording, diarization)

        val workDir = File(state.workDir, job.id).apply { mkdirs() }
        val outputBase = File(workDir, "whisper")
        val command = buildWhisperCommand(
            whisperPath = whisperPath,
            modelPath = chosenModel,
            recording = recording,
            language = job.language,
            outputBase = outputBase,
            diarization = diarization,
        )

        val result = try {
            runWhisperProcess(
                command = command,
                state = state,
                job = job,
                startedAt = startedAt,
                activeFile = recording,
                diarization = diarization,
            )
        } catch (e: Exception) {
            fail(e.localizedMessage ?: e.javaClass.simpleName)
            return
        }

        if (result.cancelled) {
            state.queue.replace(job.id) {
                it.copy(
                    status = JobStatus.Cancelled.serializedName,
                    error = "Stopped by user",
                    completedAt = Instant.now().toString(),
                )
            }
            notifier.showFailure(recording, "Stopped by user")
            return
        }

        if (result.exitCode != 0) {
            fail(result.stderr.ifBlank { result.stdout }.ifBlank { "whisper.cpp exited with ${result.exitCode}" })
            return
        }

        val rawTranscript = result.stdout.ifBlank {
            File("${outputBase.absolutePath}.txt").takeIf { it.isFile }?.readText().orEmpty()
        }
        val normalized = normalizeSpeakerTranscript(rawTranscript)
        if (!normalized.hasSpeakerLabels) {
            fail("Whisper completed but did not emit speaker labels for ${diarization.serializedName}")
            return
        }

        transcript.parentFile?.mkdirs()
        when (TranscriptFormat.from(job.format)) {
            TranscriptFormat.Txt -> transcript.writeText(normalized.text.trimEnd() + "\n")
            TranscriptFormat.Docx -> writeDocx(transcript, normalized.text)
        }

        state.queue.replace(job.id) {
            it.copy(
                status = JobStatus.Succeeded.serializedName,
                progress = 100,
                error = null,
                completedAt = Instant.now().toString(),
                transcriptPath = transcript.absolutePath,
            )
        }
        state.runtime.save(
            TranscriberRuntime(
                state = "completed",
                activeJobId = job.id,
                activeFile = recording.absolutePath,
                progress = 100,
                diarizationMode = diarization.serializedName,
            ),
        )
        notifier.showSuccess(recording, transcript)
    }

    private fun buildWhisperCommand(
        whisperPath: File,
        modelPath: File,
        recording: File,
        language: String,
        outputBase: File,
        diarization: DiarizationMode,
    ): List<String> {
        val command = mutableListOf(
            whisperPath.absolutePath,
            "-m",
            modelPath.absolutePath,
            "-f",
            recording.absolutePath,
            "-l",
            language.ifBlank { "en" },
            "-pp",
            "-otxt",
            "-oj",
            "-ojf",
            "-of",
            outputBase.absolutePath,
        )

        when (diarization) {
            DiarizationMode.Stereo -> command += "-di"
            DiarizationMode.TinyDiarize -> command += "-tdrz"
        }

        return command
    }

    private fun runWhisperProcess(
        command: List<String>,
        state: TranscriberState,
        job: TranscriptionJob,
        startedAt: Instant,
        activeFile: File,
        diarization: DiarizationMode,
    ): ProcessResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()

        fun consumeLines(
            stream: java.io.InputStream,
            sink: StringBuilder,
        ) = Thread {
            stream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(sink) {
                        sink.appendLine(line)
                    }

                    parseProgress(line)?.let { progress ->
                        val etaSeconds = estimateEtaSeconds(startedAt, progress, state.queue.load())
                        state.queue.replace(job.id) { it.copy(progress = progress) }
                        state.runtime.save(
                            TranscriberRuntime(
                                state = "running",
                                activeJobId = job.id,
                                activeFile = activeFile.absolutePath,
                                progress = progress,
                                etaSeconds = etaSeconds,
                                diarizationMode = diarization.serializedName,
                            ),
                        )
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val stdoutThread = consumeLines(process.inputStream, stdout)
        val stderrThread = consumeLines(process.errorStream, stderr)

        while (true) {
            if (state.stopFile.exists()) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
                stdoutThread.join(1_000)
                stderrThread.join(1_000)
                return ProcessResult(
                    exitCode = -1,
                    stdout = synchronized(stdout) { stdout.toString() },
                    stderr = synchronized(stderr) { stderr.toString() },
                    cancelled = true,
                )
            }

            if (process.waitFor(1, TimeUnit.SECONDS)) {
                stdoutThread.join(1_000)
                stderrThread.join(1_000)
                return ProcessResult(
                    exitCode = process.exitValue(),
                    stdout = synchronized(stdout) { stdout.toString() },
                    stderr = synchronized(stderr) { stderr.toString() },
                    cancelled = false,
                )
            }
        }
    }

    private fun normalizeSpeakerTranscript(raw: String): NormalizedTranscript {
        val speakerMap = linkedMapOf<String, String>()
        var nextSpeaker = 0
        var sawSpeaker = false
        val lines = mutableListOf<String>()
        val existingSpeaker = Regex("""^\s*(?:\[[^\]]+\]\s*)?Speaker\s+[A-Z]\s*:""")
        val speakerMarker = Regex(
            """(?i)(?:\[|\()?speaker[\s_-]*(\d+|[a-z])(?:\]|\))?\s*[:\-]*""",
        )
        val timestamp = Regex("""^(\s*\[[^\]]+\]\s*)""")

        fun labelFor(rawId: String): String {
            val key = rawId.lowercase(Locale.ROOT)
            if (key.length == 1 && key[0] in 'a'..'z') {
                return "Speaker ${key.uppercase(Locale.ROOT)}"
            }
            return speakerMap.getOrPut(key) {
                val label = "Speaker ${('A'.code + nextSpeaker).toChar()}"
                nextSpeaker += 1
                label
            }
        }

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                continue
            }
            if (
                trimmed.startsWith("whisper_", ignoreCase = true) ||
                trimmed.startsWith("main:", ignoreCase = true) ||
                trimmed.startsWith("system_info", ignoreCase = true)
            ) {
                continue
            }

            if (existingSpeaker.containsMatchIn(trimmed)) {
                sawSpeaker = true
                lines += trimmed
                continue
            }

            val marker = speakerMarker.find(trimmed)
            if (marker == null) {
                lines += trimmed
                continue
            }

            sawSpeaker = true
            val label = labelFor(marker.groupValues[1])
            val withoutMarker = trimmed.replaceRange(marker.range, "").trim()
            val timestampMatch = timestamp.find(withoutMarker)

            if (timestampMatch != null) {
                val prefix = timestampMatch.groupValues[1]
                val body = withoutMarker.removePrefix(prefix).trim()
                lines += "$prefix$label: $body"
            } else {
                lines += "$label: $withoutMarker"
            }
        }

        return NormalizedTranscript(lines.joinToString("\n"), sawSpeaker)
    }

    private fun parseProgress(line: String): Int? {
        val patterns = listOf(
            Regex("""(?i)progress\s*=\s*(\d{1,3})%"""),
            Regex("""\[\s*(\d{1,3})%\s*]"""),
            Regex("""(?i)\b(\d{1,3})%\s+done\b"""),
        )

        for (pattern in patterns) {
            val value = pattern.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (value != null) {
                return value.coerceIn(0, 100)
            }
        }

        return null
    }

    private fun estimateEtaSeconds(startedAt: Instant, progress: Int, queue: TranscriptionQueue): Long? {
        if (progress <= 0) {
            return null
        }

        val elapsed = Instant.now().epochSecond - startedAt.epochSecond
        if (elapsed <= 0) {
            return null
        }

        val activeTotal = elapsed * 100.0 / progress
        val activeRemaining = (activeTotal - elapsed).coerceAtLeast(0.0)
        val queuedRemaining = queue.jobs.count { it.status == JobStatus.Queued.serializedName }

        return (activeRemaining + activeTotal * queuedRemaining).roundToInt().toLong()
    }

    private fun dependencyStatus(
        whisperPath: File,
        modelPath: File,
        tinydiarizeModelPath: File,
    ): TranscriberDependencies = TranscriberDependencies(
        whisperPath = whisperPath.absolutePath,
        whisperExists = whisperPath.isFile,
        whisperExecutable = whisperPath.canExecute(),
        modelPath = modelPath.absolutePath,
        modelExists = modelPath.isFile,
        tinydiarizeModelPath = tinydiarizeModelPath.absolutePath,
        tinydiarizeModelExists = tinydiarizeModelPath.isFile,
        readyForStereo = whisperPath.isFile && modelPath.isFile,
        readyForMonoDiarization = whisperPath.isFile && tinydiarizeModelPath.isFile,
    )

    private fun diarizationMode(
        audioChannels: Int?,
        tinydiarizeModelPath: File,
    ): DiarizationMode {
        if (audioChannels != null && audioChannels >= 2) {
            return DiarizationMode.Stereo
        }

        if (tinydiarizeModelPath.isFile) {
            return DiarizationMode.TinyDiarize
        }

        throw IllegalStateException(
            "Mono recording requires ${tinydiarizeModelPath.absolutePath} for speaker labels. " +
                "Enable experimental stereo recording for future calls or add a TinyDiarize model.",
        )
    }

    private fun transcriptPath(transcriptDir: File, recording: File, format: TranscriptFormat): File {
        val sanitizedStem = recording.nameWithoutExtension
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .ifBlank { "recording" }

        return File(transcriptDir, "$sanitizedStem.${format.extension}")
    }

    private fun inspectWaveChannels(file: File): Int? {
        if (!file.isFile || file.length() < 24) {
            return null
        }

        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(24)
                if (stream.read(header) != header.size) {
                    return null
                }
                val riff = String(header, 0, 4, Charsets.US_ASCII)
                val wave = String(header, 8, 4, Charsets.US_ASCII)
                if (riff != "RIFF" || wave != "WAVE") {
                    return null
                }

                ByteBuffer.wrap(header, 22, 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .short
                    .toInt()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDocx(file: File, transcript: String) {
        file.parentFile?.mkdirs()

        ZipOutputStream(file.outputStream()).use { zip ->
            zip.writestr("[Content_Types].xml", CONTENT_TYPES_XML)
            zip.writestr("_rels/.rels", RELS_XML)
            zip.writestr("word/_rels/document.xml.rels", DOCUMENT_RELS_XML)
            zip.writestr("word/document.xml", buildDocumentXml(transcript))
        }
    }

    private fun ZipOutputStream.writestr(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun buildDocumentXml(transcript: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append(
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""",
        )

        for (line in transcript.lineSequence()) {
            append("<w:p><w:r><w:t xml:space=\"preserve\">")
            append(xmlEscape(line))
            append("</w:t></w:r></w:p>")
        }

        append("<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>")
        append("</w:body></w:document>")
    }

    private fun xmlEscape(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    private fun parseBoolean(value: String?, default: Boolean): Boolean = when (value?.lowercase(Locale.ROOT)) {
        null, "" -> default
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> default
    }

    private val AUDIO_EXTENSIONS = setOf("wav", "flac", "m4a", "mp3", "ogg", "opus", "aac", "amr")
    private val ACTIVE_STATUSES = setOf(JobStatus.Queued.serializedName, JobStatus.Running.serializedName)

    private const val CONTENT_TYPES_XML =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
    private const val RELS_XML =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
    private const val DOCUMENT_RELS_XML =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>"""
}

private class TranscriberState(moduleDir: File) {
    val stateDir = File(moduleDir, ".state")
    val queueFile = File(stateDir, "transcriber-queue.json")
    val runtimeFile = File(stateDir, "transcriber-runtime.json")
    val pauseFile = File(stateDir, "transcriber.pause")
    val stopFile = File(stateDir, "transcriber.stop")
    val workDir = File(stateDir, "transcriber-work")
    val queue = TranscriptionQueueStore(queueFile)
    val runtime = TranscriberRuntimeStore(runtimeFile)

    fun ensure() {
        stateDir.mkdirs()
        workDir.mkdirs()
        queue.ensureExists()
        runtime.ensureExists()
    }

    fun recoverStaleRunningJobs() {
        queue.update { current ->
            current.copy(
                jobs = current.jobs.map { job ->
                    if (job.status == JobStatus.Running.serializedName) {
                        job.copy(status = JobStatus.Queued.serializedName, progress = 0)
                    } else {
                        job
                    }
                },
            )
        }
    }
}

private class TranscriptionQueueStore(private val file: File) {
    fun ensureExists() {
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            save(TranscriptionQueue())
        }
    }

    @Synchronized
    fun load(): TranscriptionQueue {
        ensureExists()
        return try {
            HeadlessTranscriberJson.decodeFromString(file.readText())
        } catch (_: Exception) {
            TranscriptionQueue()
        }
    }

    @Synchronized
    fun update(transform: (TranscriptionQueue) -> TranscriptionQueue): TranscriptionQueue {
        val updated = transform(load())
        save(updated)
        return updated
    }

    @Synchronized
    fun replace(id: String, transform: (TranscriptionJob) -> TranscriptionJob) {
        update { queue ->
            queue.copy(jobs = queue.jobs.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun save(queue: TranscriptionQueue) {
        file.writeText(HeadlessTranscriberJson.encodeToString(queue) + "\n")
    }
}

private class TranscriberRuntimeStore(private val file: File) {
    fun ensureExists() {
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            save(TranscriberRuntime())
        }
    }

    @Synchronized
    fun load(): TranscriberRuntime {
        ensureExists()
        return try {
            HeadlessTranscriberJson.decodeFromString(file.readText())
        } catch (_: Exception) {
            TranscriberRuntime()
        }
    }

    @Synchronized
    fun save(runtime: TranscriberRuntime) {
        file.writeText(HeadlessTranscriberJson.encodeToString(runtime) + "\n")
    }

    @Synchronized
    fun update(transform: (TranscriberRuntime) -> TranscriberRuntime) {
        save(transform(load()))
    }
}

private val HeadlessTranscriberJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private class TranscriberNotifier(private val enabled: Boolean) {
    fun showStarted(recording: File, mode: DiarizationMode) {
        post("Transcribing call recording", "${recording.name}\nSpeaker mode: ${mode.serializedName}")
    }

    fun showSuccess(recording: File, transcript: File) {
        post("Transcript saved", "${recording.name}\n${transcript.absolutePath}")
    }

    fun showFailure(recording: File, error: String) {
        post("Transcription failed", "${recording.name}\n$error")
    }

    private fun post(title: String, text: String) {
        if (!enabled) {
            return
        }

        try {
            ShellCommandRunner.run(
                "cmd",
                "notification",
                "post",
                "-S",
                "bigtext",
                "-i",
                "@android:drawable/stat_sys_speakerphone",
                "-t",
                title,
                "bcr_headless_transcriber",
                text,
            )
        } catch (_: Exception) {
            // Notification failures must never affect transcription state.
        }
    }
}

@Serializable
data class TranscriptionQueue(
    val jobs: List<TranscriptionJob> = emptyList(),
)

@Serializable
data class TranscriptionJob(
    val id: String,
    val recordingPath: String,
    val transcriptPath: String,
    val language: String,
    val format: String,
    val overwrite: Boolean,
    val status: String,
    val progress: Int = 0,
    val createdAt: String,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val error: String? = null,
    val audioChannels: Int? = null,
    val diarizationMode: String? = null,
)

@Serializable
data class TranscriberRuntime(
    val state: String = "idle",
    val activeJobId: String? = null,
    val activeFile: String? = null,
    val progress: Int? = null,
    val etaSeconds: Long? = null,
    val diarizationMode: String? = null,
    val error: String? = null,
)

@Serializable
data class TranscriberStatus(
    val dependencies: TranscriberDependencies,
    val outputDir: String,
    val transcriptDir: String,
    val queue: TranscriptionQueue,
    val runtime: TranscriberRuntime,
    val paused: Boolean,
    val stopRequested: Boolean,
)

@Serializable
data class TranscriberDependencies(
    val whisperPath: String,
    val whisperExists: Boolean,
    val whisperExecutable: Boolean,
    val modelPath: String,
    val modelExists: Boolean,
    val tinydiarizeModelPath: String,
    val tinydiarizeModelExists: Boolean,
    val readyForStereo: Boolean,
    val readyForMonoDiarization: Boolean,
)

@Serializable
data class RecordingCandidate(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: String,
    val audioChannels: Int?,
    val transcriptTxtPath: String,
    val transcriptDocxPath: String,
    val transcriptTxtExists: Boolean,
    val transcriptDocxExists: Boolean,
    val selectedTranscriptExists: Boolean,
)

@Serializable
data class EnqueueResult(
    val queued: List<TranscriptionJob>,
    val skipped: List<String>,
    val conflicts: List<String>,
    val cancelled: Boolean,
)

private data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val cancelled: Boolean,
)

private data class NormalizedTranscript(
    val text: String,
    val hasSpeakerLabels: Boolean,
)

private enum class JobStatus(val serializedName: String) {
    Queued("queued"),
    Running("running"),
    Succeeded("succeeded"),
    Failed("failed"),
    Skipped("skipped"),
    Cancelled("cancelled"),
}

private enum class ConflictPolicy {
    Overwrite,
    Skip,
    Cancel;

    companion object {
        fun from(value: String): ConflictPolicy = when (value.lowercase(Locale.ROOT)) {
            "overwrite" -> Overwrite
            "skip" -> Skip
            "cancel" -> Cancel
            else -> throw IllegalArgumentException("Unknown conflict policy: $value")
        }
    }
}

private enum class TranscriptFormat(val serializedName: String, val extension: String) {
    Txt("txt", "txt"),
    Docx("docx", "docx");

    companion object {
        fun from(value: String): TranscriptFormat = when (value.lowercase(Locale.ROOT)) {
            "txt" -> Txt
            "docx" -> Docx
            else -> Txt
        }
    }
}

private enum class DiarizationMode(val serializedName: String) {
    Stereo("stereo"),
    TinyDiarize("tinydiarize"),
}
