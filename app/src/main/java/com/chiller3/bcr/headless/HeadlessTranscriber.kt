/*
 * SPDX-FileCopyrightText: 2026 wjdob
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.headless

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

object HeadlessTranscriber {
    private val JSON = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun run(args: Array<String>) {
        require(args.isNotEmpty()) {
            "Usage: transcriber [status|list|enqueue|worker|control|prepare-components|refresh-component-metadata|open-transcript] ..."
        }

        when (args[0]) {
            "status" -> runStatus(args)
            "list" -> runList(args)
            "enqueue" -> runEnqueue(args)
            "worker" -> runWorker(args)
            "control" -> runControl(args)
            "prepare-components" -> HeadlessTranscriberComponents.runPrepare(args)
            "refresh-component-metadata" -> HeadlessTranscriberComponents.runRefreshMetadata(args)
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
        require(args.size >= 8) {
            "Usage: transcriber enqueue <module_dir> <transcript_dir> <language> <format> <conflict_policy> <speaker_self_name> <recording>..."
        }

        val moduleDir = File(args[1])
        val transcriptDir = File(args[2])
        val language = args[3].ifBlank { "en" }
        val format = TranscriptFormat.from(args[4])
        val conflictPolicy = ConflictPolicy.from(args[5])
        val speakerSelfName = normalizeSpeakerName(args[6])
        val recordings = args.drop(7).map(::File)
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
                speakerSelfName = speakerSelfName,
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
            "Usage: transcriber worker <module_dir> <whisper_path> <model_path> <tinydiarize_model_path> <notifications_enabled> [speaker_self_name]"
        }

        val moduleDir = File(args[1])
        val whisperPath = File(args[2])
        val modelPath = File(args[3])
        val tinydiarizeModelPath = File(args[4])
        val notificationsEnabled = parseBoolean(args[5], default = true)
        val defaultSpeakerSelfName = normalizeSpeakerName(args.getOrNull(6))
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
                defaultSpeakerSelfName = defaultSpeakerSelfName,
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
        defaultSpeakerSelfName: String,
    ) {
        val startedAt = Instant.now()
        val recording = File(job.recordingPath)
        val transcript = File(job.transcriptPath)
        val channels = inspectWaveChannels(recording)
        val speakerSelfName = normalizeSpeakerName(job.speakerSelfName.ifBlank { defaultSpeakerSelfName })

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
        val execution = try {
            when (diarization) {
                DiarizationMode.Stereo -> transcribeStereoRecording(
                    state = state,
                    job = job,
                    startedAt = startedAt,
                    activeFile = recording,
                    whisperPath = whisperPath,
                    modelPath = modelPath,
                    language = job.language,
                    workDir = workDir,
                    speakerSelfName = speakerSelfName,
                )
                DiarizationMode.TinyDiarize -> transcribeSinglePass(
                    state = state,
                    job = job,
                    startedAt = startedAt,
                    activeFile = recording,
                    whisperPath = whisperPath,
                    modelPath = chosenModel,
                    recording = recording,
                    language = job.language,
                    outputBase = File(workDir, "whisper"),
                    diarization = diarization,
                    progressBase = 0,
                    progressSpan = 100,
                ) { outputBase, rawTranscript ->
                    buildNormalizedTranscript(
                        diarization = diarization,
                        outputBase = outputBase,
                        rawTranscript = rawTranscript,
                        speakerSelfName = speakerSelfName,
                    )
                }
            }
        } catch (e: Exception) {
            fail(e.localizedMessage ?: e.javaClass.simpleName)
            return
        }

        val normalized = when (execution) {
            TranscriptionExecutionResult.Cancelled -> {
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
            is TranscriptionExecutionResult.Failure -> {
                fail(execution.message)
                return
            }
            is TranscriptionExecutionResult.Success -> execution.transcript
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
        diarization: DiarizationMode?,
        maxLenChars: Int? = null,
        splitOnWord: Boolean = false,
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

        if (maxLenChars != null && maxLenChars > 0) {
            command += listOf("-ml", maxLenChars.toString())
        }
        if (splitOnWord) {
            command += "-sow"
        }

        when (diarization) {
            DiarizationMode.Stereo -> command += "-di"
            DiarizationMode.TinyDiarize -> command += "-tdrz"
            null -> Unit
        }

        return command
    }

    private fun transcribeSinglePass(
        state: TranscriberState,
        job: TranscriptionJob,
        startedAt: Instant,
        activeFile: File,
        whisperPath: File,
        modelPath: File,
        recording: File,
        language: String,
        outputBase: File,
        diarization: DiarizationMode?,
        progressBase: Int,
        progressSpan: Int,
        maxLenChars: Int? = null,
        splitOnWord: Boolean = false,
        normalizer: (File, String) -> NormalizedTranscript?,
    ): TranscriptionExecutionResult {
        val command = buildWhisperCommand(
            whisperPath = whisperPath,
            modelPath = modelPath,
            recording = recording,
            language = language,
            outputBase = outputBase,
            diarization = diarization,
            maxLenChars = maxLenChars,
            splitOnWord = splitOnWord,
        )

        val result = runWhisperProcess(
            command = command,
            state = state,
            job = job,
            startedAt = startedAt,
            activeFile = activeFile,
            diarization = diarization ?: DiarizationMode.Stereo,
            progressBase = progressBase,
            progressSpan = progressSpan,
        )

        if (result.cancelled) {
            return TranscriptionExecutionResult.Cancelled
        }
        if (result.exitCode != 0) {
            return TranscriptionExecutionResult.Failure(
                result.stderr.ifBlank { result.stdout }.ifBlank { "whisper.cpp exited with ${result.exitCode}" },
            )
        }

        val rawTranscript = result.stdout.ifBlank {
            File("${outputBase.absolutePath}.txt").takeIf { it.isFile }?.readText().orEmpty()
        }
        val normalized = normalizer(outputBase, rawTranscript)
            ?: return TranscriptionExecutionResult.Failure(
                "Whisper completed but did not emit timestamped transcription data",
            )
        if (!normalized.hasSpeakerLabels) {
            return TranscriptionExecutionResult.Failure(
                "Whisper completed but did not emit speaker labels for ${diarization?.serializedName ?: "stereo"}",
            )
        }

        return TranscriptionExecutionResult.Success(normalized)
    }

    private fun transcribeStereoRecording(
        state: TranscriberState,
        job: TranscriptionJob,
        startedAt: Instant,
        activeFile: File,
        whisperPath: File,
        modelPath: File,
        language: String,
        workDir: File,
        speakerSelfName: String,
    ): TranscriptionExecutionResult {
        val split = splitStereoWave(activeFile, workDir)
        if (split == null) {
            return transcribeSinglePass(
                state = state,
                job = job,
                startedAt = startedAt,
                activeFile = activeFile,
                whisperPath = whisperPath,
                modelPath = modelPath,
                recording = activeFile,
                language = language,
                outputBase = File(workDir, "whisper"),
                diarization = DiarizationMode.Stereo,
                progressBase = 0,
                progressSpan = 100,
            ) { outputBase, rawTranscript ->
                buildNormalizedTranscript(
                    diarization = DiarizationMode.Stereo,
                    outputBase = outputBase,
                    rawTranscript = rawTranscript,
                    speakerSelfName = speakerSelfName,
                )
            }
        }

        val leftSpeechRegions = detectMonoSpeechRegions(split.left)
        val rightSpeechRegions = detectMonoSpeechRegions(split.right)

        val leftResult = transcribeSinglePass(
            state = state,
            job = job,
            startedAt = startedAt,
            activeFile = activeFile,
            whisperPath = whisperPath,
            modelPath = modelPath,
            recording = split.left,
            language = language,
            outputBase = File(workDir, "whisper-left"),
            diarization = null,
            progressBase = 0,
            progressSpan = 50,
        ) { outputBase, _ ->
            normalizeChannelTranscript(
                outputBase = outputBase,
                primarySpeaker = speakerSelfName,
                speechRegions = leftSpeechRegions,
            )
        }
        when (leftResult) {
            TranscriptionExecutionResult.Cancelled -> return leftResult
            is TranscriptionExecutionResult.Failure -> {
                return TranscriptionExecutionResult.Failure("Left channel transcription failed: ${leftResult.message}")
            }
            is TranscriptionExecutionResult.Success -> Unit
        }

        val rightResult = transcribeSinglePass(
            state = state,
            job = job,
            startedAt = startedAt,
            activeFile = activeFile,
            whisperPath = whisperPath,
            modelPath = modelPath,
            recording = split.right,
            language = language,
            outputBase = File(workDir, "whisper-right"),
            diarization = null,
            progressBase = 50,
            progressSpan = 50,
        ) { outputBase, _ ->
            normalizeChannelTranscript(
                outputBase = outputBase,
                primarySpeaker = DEFAULT_REMOTE_SPEAKER_NAME,
                speechRegions = rightSpeechRegions,
            )
        }
        when (rightResult) {
            TranscriptionExecutionResult.Cancelled -> return rightResult
            is TranscriptionExecutionResult.Failure -> {
                return TranscriptionExecutionResult.Failure("Right channel transcription failed: ${rightResult.message}")
            }
            is TranscriptionExecutionResult.Success -> Unit
        }

        val utterances = mergeAdjacentUtterances(
            (
                leftResult.transcript.utterances +
                    rightResult.transcript.utterances
                )
                .sortedWith(compareBy<TranscriptUtterance> { it.startMs }.thenBy { it.endMs }.thenBy { it.speaker }),
        )
        if (utterances.isEmpty()) {
            return TranscriptionExecutionResult.Failure(
                "Whisper completed but did not emit timestamped transcription data for stereo channels",
            )
        }

        return TranscriptionExecutionResult.Success(
            NormalizedTranscript(
                text = formatTranscriptUtterances(utterances),
                hasSpeakerLabels = true,
                utterances = utterances,
            ),
        )
    }

    private fun runWhisperProcess(
        command: List<String>,
        state: TranscriberState,
        job: TranscriptionJob,
        startedAt: Instant,
        activeFile: File,
        diarization: DiarizationMode,
        progressBase: Int,
        progressSpan: Int,
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
                        val scaledProgress = scaleProgress(progress, progressBase, progressSpan)
                        val etaSeconds = estimateEtaSeconds(startedAt, scaledProgress, state.queue.load())
                        state.queue.replace(job.id) { it.copy(progress = scaledProgress) }
                        state.runtime.save(
                            TranscriberRuntime(
                                state = "running",
                                activeJobId = job.id,
                                activeFile = activeFile.absolutePath,
                                progress = scaledProgress,
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

    private fun buildNormalizedTranscript(
        diarization: DiarizationMode,
        outputBase: File,
        rawTranscript: String,
        speakerSelfName: String,
    ): NormalizedTranscript = when (diarization) {
        DiarizationMode.Stereo -> normalizeStereoTranscript(outputBase, speakerSelfName) ?: normalizeSpeakerTranscript(rawTranscript, speakerSelfName)
        DiarizationMode.TinyDiarize -> normalizeTinydiarizeTranscript(outputBase, speakerSelfName) ?: normalizeSpeakerTranscript(rawTranscript, speakerSelfName)
    }

    private fun normalizeStereoTranscript(outputBase: File, speakerSelfName: String): NormalizedTranscript? {
        val transcription = readWhisperTranscription(outputBase) ?: return null
        val pending = transcription.mapNotNull { segment ->
            val offsets = segment.offsets ?: return@mapNotNull null
            val text = cleanTranscriptFragment(segment.text)
            if (text.isEmpty()) {
                return@mapNotNull null
            }

            PendingTranscriptSpan(
                startMs = offsets.from.coerceAtLeast(0),
                endMs = offsets.to.coerceAtLeast(offsets.from),
                speaker = mapStereoSpeaker(segment.speaker, speakerSelfName),
                text = text,
            )
        }
        if (pending.isEmpty()) {
            return null
        }

        val spans = resolvePendingStereoSpeakers(pending)

        val utterances = regroupStereoSpans(spans)
        if (utterances.isEmpty()) {
            return null
        }

        return NormalizedTranscript(
            text = formatTranscriptUtterances(utterances),
            hasSpeakerLabels = utterances.isNotEmpty(),
            utterances = utterances,
        )
    }

    private fun resolvePendingStereoSpeakers(pending: List<PendingTranscriptSpan>): List<TranscriptSpan> {
        val resolved = mutableListOf<TranscriptSpan>()
        var lastSpeaker = pending.firstNotNullOfOrNull { it.speaker } ?: "Speaker A"

        for (index in pending.indices) {
            val current = pending[index]
            val speaker = current.speaker
                ?: pending.drop(index + 1).firstNotNullOfOrNull { it.speaker }
                ?: lastSpeaker
            lastSpeaker = speaker
            resolved += TranscriptSpan(
                startMs = current.startMs,
                endMs = current.endMs,
                speaker = speaker,
                text = current.text,
            )
        }

        return resolved
    }

    private fun normalizeTinydiarizeTranscript(outputBase: File, speakerSelfName: String): NormalizedTranscript? {
        val transcription = readWhisperTranscription(outputBase) ?: return null
        val utterances = mutableListOf<TranscriptUtterance>()
        var speakerIndex = 0

        for (segment in transcription) {
            val offsets = segment.offsets ?: continue
            val text = cleanTranscriptFragment(segment.text)
                .replace(" [SPEAKER_TURN]", "")
                .trim()
            if (text.isEmpty()) {
                if (segment.speakerTurnNext) {
                    speakerIndex = (speakerIndex + 1) % 2
                }
                continue
            }

            val speaker = if (speakerIndex % 2 == 0) {
                speakerSelfName
            } else {
                DEFAULT_REMOTE_SPEAKER_NAME
            }
            utterances += TranscriptUtterance(
                startMs = offsets.from.coerceAtLeast(0),
                endMs = offsets.to.coerceAtLeast(offsets.from),
                speaker = speaker,
                text = text,
            )

            if (segment.speakerTurnNext) {
                speakerIndex = (speakerIndex + 1) % 2
            }
        }

        if (utterances.isEmpty()) {
            return null
        }

        val merged = mergeAdjacentUtterances(utterances)
        return NormalizedTranscript(
            text = formatTranscriptUtterances(merged),
            hasSpeakerLabels = merged.isNotEmpty(),
            utterances = merged,
        )
    }

    private fun readWhisperTranscription(outputBase: File): List<WhisperTranscriptionSegment>? {
        val file = File("${outputBase.absolutePath}.json")
        if (!file.isFile) {
            return null
        }

        return try {
            val payload = JSON.decodeFromString<WhisperTranscriptionPayload>(file.readText())
            payload.transcription
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeChannelTranscript(
        outputBase: File,
        primarySpeaker: String,
        alternateSpeaker: String? = null,
        speechRegions: List<SpeechRegion>? = null,
    ): NormalizedTranscript? {
        val transcription = readWhisperTranscription(outputBase) ?: return null
        val segmentUtterances = buildChannelSegmentUtterances(
            transcription = transcription,
            speaker = primarySpeaker,
            speechRegions = speechRegions,
        )
        if (segmentUtterances.isNotEmpty()) {
            return NormalizedTranscript(
                text = formatTranscriptUtterances(segmentUtterances),
                hasSpeakerLabels = true,
                utterances = segmentUtterances,
            )
        }

        val spans = buildChannelTranscriptSpans(transcription, primarySpeaker, alternateSpeaker)
        if (spans.isEmpty()) {
            return null
        }

        val merged = regroupChannelSpans(spans, speechRegions)
        if (merged.isEmpty()) {
            return null
        }

        return NormalizedTranscript(
            text = formatTranscriptUtterances(merged),
            hasSpeakerLabels = merged.isNotEmpty(),
            utterances = merged,
        )
    }

    private fun buildChannelSegmentUtterances(
        transcription: List<WhisperTranscriptionSegment>,
        speaker: String,
        speechRegions: List<SpeechRegion>?,
    ): List<TranscriptUtterance> {
        val chunks = transcription.flatMap { segment ->
            val text = cleanTranscriptFragment(segment.text)
            if (text.isEmpty()) {
                return@flatMap emptyList()
            }

            val offsets = segment.offsets
            val pieces = splitTranscriptIntoChunks(text)
            if (pieces.isEmpty()) {
                emptyList()
            } else if (offsets == null || pieces.size == 1) {
                pieces.map {
                    ChannelTextChunk(
                        startMs = offsets?.from?.coerceAtLeast(0),
                        endMs = offsets?.to?.coerceAtLeast(offsets.from),
                        text = it,
                    )
                }
            } else {
                distributeChunksAcrossRange(
                    chunks = pieces,
                    startMs = offsets.from.coerceAtLeast(0),
                    endMs = offsets.to.coerceAtLeast(offsets.from),
                )
            }
        }

        if (chunks.isEmpty()) {
            return emptyList()
        }

        val regions = speechRegions.orEmpty()
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
        val utterances = if (regions.isNotEmpty()) {
            assignChunksToSpeechRegions(chunks, regions, speaker)
        } else {
            chunks.mapIndexed { index, chunk ->
                val start = chunk.startMs ?: (index * 1000L)
                val end = chunk.endMs?.coerceAtLeast(start + 1L) ?: (start + estimateChunkDurationMs(chunk.text))
                TranscriptUtterance(
                    startMs = start,
                    endMs = end,
                    speaker = speaker,
                    text = chunk.text,
                )
            }
        }

        return mergeAdjacentUtterances(
            utterances
                .filter { it.text.isNotBlank() }
                .sortedWith(compareBy<TranscriptUtterance> { it.startMs }.thenBy { it.endMs }),
        )
    }

    private fun distributeChunksAcrossRange(
        chunks: List<String>,
        startMs: Long,
        endMs: Long,
    ): List<ChannelTextChunk> {
        val duration = (endMs - startMs).coerceAtLeast(chunks.size.toLong())
        val weights = chunks.map { estimateChunkWeight(it) }
        val totalWeight = weights.sum().takeIf { it > 0 } ?: chunks.size.toLong()
        var cursor = startMs

        return chunks.mapIndexed { index, text ->
            val isLast = index == chunks.lastIndex
            val chunkDuration = if (isLast) {
                endMs - cursor
            } else {
                (duration * weights[index] / totalWeight).coerceAtLeast(1L)
            }
            val chunkEnd = if (isLast) {
                endMs
            } else {
                (cursor + chunkDuration).coerceAtMost(endMs)
            }
            ChannelTextChunk(
                startMs = cursor,
                endMs = chunkEnd.coerceAtLeast(cursor + 1L),
                text = text,
            ).also {
                cursor = chunkEnd
            }
        }
    }

    private fun assignChunksToSpeechRegions(
        chunks: List<ChannelTextChunk>,
        regions: List<SpeechRegion>,
        speaker: String,
    ): List<TranscriptUtterance> {
        val utterances = mutableListOf<TranscriptUtterance>()
        var regionIndex = 0

        for ((chunkIndex, chunk) in chunks.withIndex()) {
            val region = chooseSpeechRegionForChunk(chunk, regions, regionIndex)
            if (region != null) {
                regionIndex = (regions.indexOf(region) + 1).coerceAtMost(regions.size)
            }

            val fallbackStart = chunk.startMs ?: utterances.lastOrNull()?.endMs ?: (chunkIndex * 1000L)
            val start = region?.startMs ?: fallbackStart
            val end = region?.endMs
                ?: chunk.endMs?.coerceAtLeast(start + 1L)
                ?: (start + estimateChunkDurationMs(chunk.text))

            utterances += TranscriptUtterance(
                startMs = start,
                endMs = end.coerceAtLeast(start + 1L),
                speaker = speaker,
                text = chunk.text,
            )
        }

        return utterances
    }

    private fun chooseSpeechRegionForChunk(
        chunk: ChannelTextChunk,
        regions: List<SpeechRegion>,
        startIndex: Int,
    ): SpeechRegion? {
        if (startIndex >= regions.size) {
            return null
        }

        val start = chunk.startMs
        val end = chunk.endMs
        if (start == null || end == null) {
            return regions[startIndex]
        }

        val overlapping = regions
            .drop(startIndex)
            .firstOrNull { region ->
                rangesOverlap(
                    start - SPEECH_REGION_ASSIGN_TOLERANCE_MS,
                    end + SPEECH_REGION_ASSIGN_TOLERANCE_MS,
                    region.startMs,
                    region.endMs,
                )
            }
        if (overlapping != null) {
            return overlapping
        }

        return regions[startIndex]
    }

    private fun splitTranscriptIntoChunks(text: String): List<String> {
        val cleaned = cleanTranscriptFragment(text)
        if (cleaned.isEmpty()) {
            return emptyList()
        }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (index in cleaned.indices) {
            val char = cleaned[index]
            current.append(char)

            val atEnd = index == cleaned.lastIndex
            val next = cleaned.getOrNull(index + 1)
            if ((char == '.' || char == '?' || char == '!') && (atEnd || next?.isWhitespace() == true)) {
                chunks += current.toString().trim()
                current.clear()
            }
        }

        val tail = current.toString().trim()
        if (tail.isNotEmpty()) {
            chunks += tail
        }

        return chunks.ifEmpty { listOf(cleaned) }
            .flatMap { splitLongTranscriptChunk(it) }
            .filter { it.isNotBlank() }
    }

    private fun splitLongTranscriptChunk(text: String): List<String> {
        if (text.length <= 140) {
            return listOf(text)
        }

        val parts = text.split(Regex("""(?<=,)\s+"""))
        if (parts.size <= 1) {
            return listOf(text)
        }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (part in parts) {
            if (current.isNotEmpty() && current.length + part.length + 1 > 140) {
                chunks += current.toString().trim()
                current.clear()
            }
            if (current.isNotEmpty()) {
                current.append(' ')
            }
            current.append(part)
        }

        val tail = current.toString().trim()
        if (tail.isNotEmpty()) {
            chunks += tail
        }
        return chunks
    }

    private fun estimateChunkWeight(text: String): Long =
        text.split(Regex("""\s+""")).count { it.isNotBlank() }.coerceAtLeast(1).toLong()

    private fun estimateChunkDurationMs(text: String): Long =
        (estimateChunkWeight(text) * 420L).coerceIn(600L, 8_000L)

    private fun rangesOverlap(
        firstStart: Long,
        firstEnd: Long,
        secondStart: Long,
        secondEnd: Long,
    ): Boolean = firstStart < secondEnd && secondStart < firstEnd

    private fun buildChannelTranscriptSpans(
        transcription: List<WhisperTranscriptionSegment>,
        primarySpeaker: String,
        alternateSpeaker: String? = null,
    ): List<TranscriptSpan> {
        val spans = mutableListOf<TranscriptSpan>()
        var speakerIndex = 0

        for (segment in transcription) {
            val speaker = if (alternateSpeaker != null && speakerIndex % 2 == 1) {
                alternateSpeaker
            } else {
                primarySpeaker
            }

            val tokenSpans = segment.tokens.mapNotNull { token ->
                val offsets = token.offsets ?: return@mapNotNull null
                val text = cleanTokenFragment(token.text)
                if (text.isEmpty()) {
                    return@mapNotNull null
                }

                TranscriptSpan(
                    startMs = offsets.from.coerceAtLeast(0),
                    endMs = offsets.to.coerceAtLeast(offsets.from),
                    speaker = speaker,
                    text = text,
                )
            }

            if (tokenSpans.isNotEmpty()) {
                spans += tokenSpans
            } else {
                val offsets = segment.offsets
                val text = cleanTokenFragment(segment.text)
                if (offsets != null && text.isNotEmpty()) {
                    spans += TranscriptSpan(
                        startMs = offsets.from.coerceAtLeast(0),
                        endMs = offsets.to.coerceAtLeast(offsets.from),
                        speaker = speaker,
                        text = text,
                    )
                }
            }

            if (alternateSpeaker != null && segment.speakerTurnNext) {
                speakerIndex = (speakerIndex + 1) % 2
            }
        }

        return spans
    }

    private fun regroupChannelSpans(
        spans: List<TranscriptSpan>,
        speechRegions: List<SpeechRegion>?,
    ): List<TranscriptUtterance> {
        if (spans.isEmpty()) {
            return emptyList()
        }
        if (speechRegions.isNullOrEmpty()) {
            return regroupStereoSpans(spans)
        }

        val assignments = spans.mapNotNull { span ->
            val regionIndex = findSpeechRegionIndex(span, speechRegions) ?: return@mapNotNull null
            regionIndex to span
        }
        if (assignments.isEmpty()) {
            return regroupStereoSpans(spans)
        }

        return assignments
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .flatMap { (regionIndex, regionSpans) ->
                val region = speechRegions[regionIndex]
                regroupStereoSpans(regionSpans)
                    .map { utterance ->
                        val startMs = maxOf(region.startMs, utterance.startMs)
                        val endMs = minOf(region.endMs, maxOf(utterance.endMs, startMs + 1L))
                        utterance.copy(startMs = startMs, endMs = endMs)
                    }
            }
            .sortedWith(compareBy<TranscriptUtterance> { it.startMs }.thenBy { it.endMs })
    }

    private fun findSpeechRegionIndex(
        span: TranscriptSpan,
        speechRegions: List<SpeechRegion>,
    ): Int? {
        val center = (span.startMs + span.endMs) / 2
        val direct = speechRegions.indexOfFirst { region ->
            center in (region.startMs - SPEECH_REGION_ASSIGN_TOLERANCE_MS)..(region.endMs + SPEECH_REGION_ASSIGN_TOLERANCE_MS)
        }
        if (direct >= 0) {
            return direct
        }

        var bestIndex: Int? = null
        var bestOverlap = 0L
        for ((index, region) in speechRegions.withIndex()) {
            val overlap = minOf(span.endMs, region.endMs) - maxOf(span.startMs, region.startMs)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestIndex = index
            }
        }

        return bestIndex
    }

    private fun regroupStereoSpans(spans: List<TranscriptSpan>): List<TranscriptUtterance> {
        if (spans.isEmpty()) {
            return emptyList()
        }

        val sorted = spans.sortedWith(compareBy<TranscriptSpan> { it.startMs }.thenBy { it.endMs })
        val utterances = mutableListOf<TranscriptUtterance>()
        var currentSpeaker: String? = null
        var currentStart = 0L
        var currentEnd = 0L
        val currentText = StringBuilder()

        fun flushCurrent() {
            val speaker = currentSpeaker ?: return
            val text = currentText.toString().trim()
            if (text.isNotEmpty()) {
                utterances += TranscriptUtterance(
                    startMs = currentStart,
                    endMs = currentEnd,
                    speaker = speaker,
                    text = text,
                )
            }

            currentSpeaker = null
            currentStart = 0L
            currentEnd = 0L
            currentText.clear()
        }

        for (index in sorted.indices) {
            val span = sorted[index]
            if (currentSpeaker == null) {
                currentSpeaker = span.speaker
                currentStart = span.startMs
                currentEnd = span.endMs
            } else if (currentSpeaker != span.speaker) {
                flushCurrent()
                currentSpeaker = span.speaker
                currentStart = span.startMs
                currentEnd = span.endMs
            } else {
                currentEnd = maxOf(currentEnd, span.endMs)
            }

            appendTranscriptFragment(currentText, span.text)

            val next = sorted.getOrNull(index + 1)
            val nextGap = next?.let { it.startMs - currentEnd } ?: Long.MAX_VALUE
            val shouldFlush =
                next == null ||
                    next.speaker != currentSpeaker ||
                    nextGap > 900L ||
                    (endsSentence(currentText) && nextGap > 250L) ||
                    currentText.length >= 160

            if (shouldFlush) {
                flushCurrent()
            }
        }

        return mergeAdjacentUtterances(utterances)
    }

    private fun mergeAdjacentUtterances(utterances: List<TranscriptUtterance>): List<TranscriptUtterance> {
        if (utterances.isEmpty()) {
            return emptyList()
        }

        val merged = mutableListOf<TranscriptUtterance>()
        for (utterance in utterances.sortedWith(compareBy<TranscriptUtterance> { it.startMs }.thenBy { it.endMs })) {
            val previous = merged.lastOrNull()
            if (
                previous != null &&
                previous.speaker == utterance.speaker &&
                utterance.startMs - previous.endMs <= 350L &&
                previous.text.length + utterance.text.length < 180
            ) {
                merged[merged.lastIndex] = previous.copy(
                    endMs = maxOf(previous.endMs, utterance.endMs),
                    text = joinTranscriptText(previous.text, utterance.text),
                )
            } else {
                merged += utterance
            }
        }

        return merged
    }

    private fun formatTranscriptUtterances(utterances: List<TranscriptUtterance>): String = utterances.joinToString("\n") { utterance ->
        "[${formatTranscriptTimestamp(utterance.startMs)} --> ${formatTranscriptTimestamp(utterance.endMs)}] ${utterance.speaker}: ${utterance.text}"
    }

    private fun appendTranscriptFragment(builder: StringBuilder, fragment: String) {
        val cleaned = cleanTranscriptFragment(fragment)
        if (cleaned.isEmpty()) {
            return
        }

        if (builder.isEmpty()) {
            builder.append(cleaned)
            return
        }

        if (needsSpaceBefore(cleaned)) {
            builder.append(' ')
        }
        builder.append(cleaned)
    }

    private fun joinTranscriptText(left: String, right: String): String =
        buildString {
            append(left.trim())
            if (needsSpaceBefore(right.trim())) {
                append(' ')
            }
            append(right.trim())
        }.trim()

    private fun cleanTranscriptFragment(text: String): String =
        text
            .replace(WHISPER_CONTROL_TOKEN_PATTERN, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun cleanTokenFragment(text: String): String =
        if (isWhisperControlToken(text)) {
            ""
        } else {
            cleanTranscriptFragment(text)
        }

    private fun isWhisperControlToken(text: String): Boolean =
        WHISPER_CONTROL_TOKEN_PATTERN.matches(text.trim())

    private fun needsSpaceBefore(fragment: String): Boolean {
        if (fragment.isEmpty()) {
            return false
        }

        val first = fragment.first()
        return first !in ",.!?;:)]}%'’"
    }

    private fun endsSentence(builder: StringBuilder): Boolean {
        val text = builder.toString().trimEnd()
        if (text.isEmpty()) {
            return false
        }

        return text.last() in ".!?"
    }

    private fun formatTranscriptTimestamp(totalMs: Long): String {
        val safeMs = totalMs.coerceAtLeast(0)
        val hours = safeMs / 3_600_000
        val minutes = (safeMs % 3_600_000) / 60_000
        val seconds = (safeMs % 60_000) / 1_000
        val millis = safeMs % 1_000
        return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    }

    private fun mapStereoSpeaker(rawSpeaker: String?, speakerSelfName: String): String? {
        val normalized = rawSpeaker
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace(" ", "")
            ?: return null

        return when (normalized) {
            "0", "a", "speakera" -> speakerSelfName
            "1", "b", "speakerb" -> DEFAULT_REMOTE_SPEAKER_NAME
            else -> null
        }
    }

    private fun normalizeSpeakerTranscript(raw: String, speakerSelfName: String): NormalizedTranscript {
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
                return when (key) {
                    "a" -> speakerSelfName
                    "b" -> DEFAULT_REMOTE_SPEAKER_NAME
                    else -> "Speaker ${key.uppercase(Locale.ROOT)}"
                }
            }
            return speakerMap.getOrPut(key) {
                val label = when (nextSpeaker) {
                    0 -> speakerSelfName
                    1 -> DEFAULT_REMOTE_SPEAKER_NAME
                    else -> "Speaker ${('A'.code + nextSpeaker).toChar()}"
                }
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

    private fun scaleProgress(
        progress: Int,
        base: Int,
        span: Int,
    ): Int = (base + (progress.coerceIn(0, 100) * span / 100.0).roundToInt()).coerceIn(0, 100)

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
        return readWaveMetadata(file)?.channelCount
    }

    private fun detectMonoSpeechRegions(recording: File): List<SpeechRegion>? {
        val metadata = readWaveMetadata(recording) ?: return null
        if (
            metadata.audioFormat != 1 ||
            metadata.channelCount != 1 ||
            metadata.bitsPerSample != 16 ||
            metadata.blockAlign <= 0 ||
            metadata.sampleRate <= 0
        ) {
            return null
        }

        val totalSamples = metadata.dataSize / metadata.blockAlign
        if (totalSamples <= 0) {
            return emptyList()
        }

        val samplesPerWindow = maxOf(1, metadata.sampleRate * SPEECH_WINDOW_MS / 1000)
        val frames = mutableListOf<SpeechFrame>()

        RandomAccessFile(recording, "r").use { input ->
            input.seek(metadata.dataOffset)
            val buffer = ByteArray(samplesPerWindow * metadata.blockAlign)
            var samplesRead = 0L

            while (samplesRead < totalSamples) {
                val samplesToRead = minOf(samplesPerWindow.toLong(), totalSamples - samplesRead).toInt()
                val bytesToRead = samplesToRead * metadata.blockAlign
                input.readFully(buffer, 0, bytesToRead)

                var sumAbs = 0.0
                var offset = 0
                repeat(samplesToRead) {
                    val sample = readSigned16LittleEndian(buffer, offset)
                    sumAbs += abs(sample).coerceAtMost(32767) / 32768.0
                    offset += metadata.blockAlign
                }

                val startMs = samplesRead * 1000L / metadata.sampleRate
                val endMs = (samplesRead + samplesToRead) * 1000L / metadata.sampleRate
                frames += SpeechFrame(
                    startMs = startMs,
                    endMs = endMs,
                    energy = sumAbs / samplesToRead,
                )
                samplesRead += samplesToRead
            }
        }

        if (frames.isEmpty()) {
            return emptyList()
        }

        val sortedEnergies = frames.map { it.energy }.sorted()
        val noiseFloor = percentile(sortedEnergies, 0.20)
        val highEnergy = percentile(sortedEnergies, 0.95)
        if (highEnergy < 0.0015) {
            return emptyList()
        }
        val threshold = maxOf(0.0025, noiseFloor * 3.5, highEnergy * 0.08)
        val durationMs = totalSamples * 1000L / metadata.sampleRate
        val regions = mutableListOf<SpeechRegion>()
        var currentStart: Long? = null
        var lastActiveEnd = 0L

        fun addRegion(startMs: Long, endMs: Long) {
            val start = startMs.coerceAtLeast(0)
            val end = minOf(durationMs, endMs.coerceAtLeast(start))
            if (end - start < SPEECH_MIN_REGION_MS) {
                return
            }

            val previous = regions.lastOrNull()
            if (previous != null && previous.endMs >= start) {
                regions[regions.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, end))
            } else {
                regions += SpeechRegion(startMs = start, endMs = end)
            }
        }

        for (frame in frames) {
            if (frame.energy < threshold) {
                continue
            }

            val paddedStart = (frame.startMs - SPEECH_PAD_START_MS).coerceAtLeast(0)
            val activeStart = currentStart
            if (activeStart == null) {
                currentStart = paddedStart
            } else if (frame.startMs - lastActiveEnd > SPEECH_MAX_SILENCE_GAP_MS) {
                addRegion(activeStart, lastActiveEnd + SPEECH_PAD_END_MS)
                currentStart = paddedStart
            }
            lastActiveEnd = frame.endMs
        }

        currentStart?.let { addRegion(it, lastActiveEnd + SPEECH_PAD_END_MS) }
        return regions
    }

    private fun splitStereoWave(recording: File, workDir: File): StereoSplitFiles? {
        if (recording.extension.lowercase(Locale.ROOT) != "wav") {
            return null
        }

        val metadata = readWaveMetadata(recording) ?: return null
        if (
            metadata.audioFormat != 1 ||
            metadata.channelCount != 2 ||
            metadata.bitsPerSample <= 0 ||
            metadata.bitsPerSample % 8 != 0
        ) {
            return null
        }

        val bytesPerSample = metadata.bitsPerSample / 8
        val expectedBlockAlign = metadata.channelCount * bytesPerSample
        if (metadata.blockAlign != expectedBlockAlign || metadata.blockAlign <= 0) {
            return null
        }

        val frameCount = metadata.dataSize / metadata.blockAlign
        if (frameCount <= 0) {
            return null
        }

        val left = File(workDir, "${recording.nameWithoutExtension}-left.wav")
        val right = File(workDir, "${recording.nameWithoutExtension}-right.wav")
        val monoDataSize = frameCount * bytesPerSample.toLong()

        RandomAccessFile(recording, "r").use { input ->
            input.seek(metadata.dataOffset)
            BufferedOutputStream(left.outputStream()).use { leftOut ->
                BufferedOutputStream(right.outputStream()).use { rightOut ->
                    writeWaveHeader(
                        output = leftOut,
                        sampleRate = metadata.sampleRate,
                        channelCount = 1,
                        bitsPerSample = metadata.bitsPerSample,
                        dataSize = monoDataSize,
                    )
                    writeWaveHeader(
                        output = rightOut,
                        sampleRate = metadata.sampleRate,
                        channelCount = 1,
                        bitsPerSample = metadata.bitsPerSample,
                        dataSize = monoDataSize,
                    )

                    val framesPerChunk = 2048
                    val buffer = ByteArray(metadata.blockAlign * framesPerChunk)
                    var framesRemaining = frameCount

                    while (framesRemaining > 0) {
                        val framesToRead = minOf(framesPerChunk.toLong(), framesRemaining).toInt()
                        val bytesToRead = framesToRead * metadata.blockAlign
                        input.readFully(buffer, 0, bytesToRead)

                        var offset = 0
                        repeat(framesToRead) {
                            leftOut.write(buffer, offset, bytesPerSample)
                            rightOut.write(buffer, offset + bytesPerSample, bytesPerSample)
                            offset += metadata.blockAlign
                        }

                        framesRemaining -= framesToRead
                    }
                }
            }
        }

        return StereoSplitFiles(left = left, right = right)
    }

    private fun readWaveMetadata(file: File): WaveMetadata? {
        if (!file.isFile || file.length() < 44) {
            return null
        }

        return try {
            RandomAccessFile(file, "r").use { input ->
                val header = ByteArray(12)
                input.readFully(header)
                val riff = String(header, 0, 4, Charsets.US_ASCII)
                val wave = String(header, 8, 4, Charsets.US_ASCII)
                if (riff != "RIFF" || wave != "WAVE") {
                    return null
                }

                var audioFormat: Int? = null
                var channelCount: Int? = null
                var sampleRate: Int? = null
                var blockAlign: Int? = null
                var bitsPerSample: Int? = null
                var dataOffset: Long? = null
                var dataSize: Long? = null

                while (input.filePointer + 8 <= input.length()) {
                    val chunkId = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                    val chunkSize = readLittleEndianInt(input).toLong() and 0xffffffffL
                    val chunkDataOffset = input.filePointer

                    when (chunkId) {
                        "fmt " -> {
                            if (chunkSize < 16) {
                                return null
                            }

                            audioFormat = readLittleEndianShort(input)
                            channelCount = readLittleEndianShort(input)
                            sampleRate = readLittleEndianInt(input)
                            readLittleEndianInt(input) // byte rate
                            blockAlign = readLittleEndianShort(input)
                            bitsPerSample = readLittleEndianShort(input)
                        }
                        "data" -> {
                            dataOffset = chunkDataOffset
                            dataSize = chunkSize
                        }
                    }

                    input.seek(chunkDataOffset + chunkSize + (chunkSize and 1L))
                }

                val resolvedAudioFormat = audioFormat ?: return null
                val resolvedChannelCount = channelCount ?: return null
                val resolvedSampleRate = sampleRate ?: return null
                val resolvedBlockAlign = blockAlign ?: return null
                val resolvedBitsPerSample = bitsPerSample ?: return null
                val resolvedDataOffset = dataOffset ?: return null
                val resolvedDataSize = dataSize ?: return null

                WaveMetadata(
                    audioFormat = resolvedAudioFormat,
                    channelCount = resolvedChannelCount,
                    sampleRate = resolvedSampleRate,
                    blockAlign = resolvedBlockAlign,
                    bitsPerSample = resolvedBitsPerSample,
                    dataOffset = resolvedDataOffset,
                    dataSize = resolvedDataSize,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeWaveHeader(
        output: OutputStream,
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int,
        dataSize: Long,
    ) {
        val bytesPerSample = bitsPerSample / 8
        val blockAlign = channelCount * bytesPerSample
        val byteRate = sampleRate * blockAlign
        val chunkSize = if (dataSize >= Int.MAX_VALUE) 0 else dataSize.toInt() + 36
        val resolvedDataSize = if (dataSize >= Int.MAX_VALUE) 0 else dataSize.toInt()

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(chunkSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channelCount.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(resolvedDataSize)
            flip()
        }

        output.write(header.array(), 0, header.remaining())
    }

    private fun readLittleEndianShort(input: RandomAccessFile): Int =
        java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(input.readShort()))

    private fun readLittleEndianInt(input: RandomAccessFile): Int =
        Integer.reverseBytes(input.readInt())

    private fun readSigned16LittleEndian(buffer: ByteArray, offset: Int): Int {
        val low = buffer[offset].toInt() and 0xff
        val high = buffer[offset + 1].toInt()
        return ((high shl 8) or low).toShort().toInt()
    }

    private fun percentile(sortedValues: List<Double>, percentile: Double): Double {
        if (sortedValues.isEmpty()) {
            return 0.0
        }

        val index = ((sortedValues.size - 1) * percentile)
            .roundToInt()
            .coerceIn(0, sortedValues.lastIndex)
        return sortedValues[index]
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

    private fun normalizeSpeakerName(value: String?): String =
        value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_SELF_SPEAKER_NAME

    private val AUDIO_EXTENSIONS = setOf("wav", "flac", "m4a", "mp3", "ogg", "opus", "aac", "amr")
    private val ACTIVE_STATUSES = setOf(JobStatus.Queued.serializedName, JobStatus.Running.serializedName)
    private val WHISPER_CONTROL_TOKEN_PATTERN =
        Regex("""(?i)<\|[^|]+?\|>|\[_[A-Z0-9]+(?:_[A-Z0-9]+)*_?\]|\[SPEAKER[ _]TURN\]""")
    private const val DEFAULT_SELF_SPEAKER_NAME = "Speaker A"
    private const val DEFAULT_REMOTE_SPEAKER_NAME = "Speaker B"
    private const val SPEECH_REGION_ASSIGN_TOLERANCE_MS = 400L
    private const val SPEECH_WINDOW_MS = 50
    private const val SPEECH_PAD_START_MS = 160L
    private const val SPEECH_PAD_END_MS = 220L
    private const val SPEECH_MAX_SILENCE_GAP_MS = 360L
    private const val SPEECH_MIN_REGION_MS = 220L

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
    val speakerSelfName: String = "Speaker A",
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
    val utterances: List<TranscriptUtterance> = emptyList(),
)

private sealed interface TranscriptionExecutionResult {
    data class Success(val transcript: NormalizedTranscript) : TranscriptionExecutionResult
    data class Failure(val message: String) : TranscriptionExecutionResult
    data object Cancelled : TranscriptionExecutionResult
}

@Serializable
private data class WhisperTranscriptionPayload(
    val transcription: List<WhisperTranscriptionSegment> = emptyList(),
)

@Serializable
private data class WhisperTranscriptionSegment(
    val text: String = "",
    val speaker: String? = null,
    val offsets: WhisperSegmentOffsets? = null,
    val tokens: List<WhisperToken> = emptyList(),
    @SerialName("speaker_turn_next")
    val speakerTurnNext: Boolean = false,
)

@Serializable
private data class WhisperSegmentOffsets(
    val from: Long = 0,
    val to: Long = 0,
)

@Serializable
private data class WhisperToken(
    val text: String = "",
    val id: Long? = null,
    val offsets: WhisperSegmentOffsets? = null,
)

private data class TranscriptSpan(
    val startMs: Long,
    val endMs: Long,
    val speaker: String,
    val text: String,
)

private data class SpeechRegion(
    val startMs: Long,
    val endMs: Long,
)

private data class SpeechFrame(
    val startMs: Long,
    val endMs: Long,
    val energy: Double,
)

private data class ChannelTextChunk(
    val startMs: Long?,
    val endMs: Long?,
    val text: String,
)

private data class PendingTranscriptSpan(
    val startMs: Long,
    val endMs: Long,
    val speaker: String?,
    val text: String,
)

private data class TranscriptUtterance(
    val startMs: Long,
    val endMs: Long,
    val speaker: String,
    val text: String,
)

private data class WaveMetadata(
    val audioFormat: Int,
    val channelCount: Int,
    val sampleRate: Int,
    val blockAlign: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Long,
)

private data class StereoSplitFiles(
    val left: File,
    val right: File,
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
