/*
 * SPDX-FileCopyrightText: 2026 wjdob
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.headless

import android.os.Build
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.LinkedHashMap
import java.util.Locale
import java.util.zip.ZipInputStream

object HeadlessTranscriberComponents {
    private const val HTTP_CONNECT_TIMEOUT_MS = 30_000
    private const val HTTP_READ_TIMEOUT_MS = 120_000
    private const val HTTP_REDIRECT_LIMIT = 10
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_UPDATE_BYTES = 512 * 1024L
    private const val HELPER_USER_AGENT = "BCR-Headless-Test/1.1"

    fun runPrepare(args: Array<String>) {
        require(args.size >= 10) {
            "Usage: transcriber prepare-components <module_dir> <whisper_path> <whisper_manifest_url> <whisper_url> <whisper_local_path> <model_path> <model_url> <tinydiarize_model_path> <tinydiarize_model_url>"
        }

        val moduleDir = File(args[1])
        val whisperPath = File(args[2])
        val whisperManifestUrl = args[3].trim()
        val whisperUrl = args[4].trim()
        val whisperLocalPath = args[5].trim().takeIf { it.isNotEmpty() }?.let(::File)
        val modelPath = File(args[6])
        val modelUrl = args[7].trim()
        val tinydiarizeModelPath = File(args[8])
        val tinydiarizeModelUrl = args[9].trim()
        val installer = ComponentInstaller(
            state = ComponentInstallerState(moduleDir),
            whisperPath = whisperPath,
            whisperManifestUrl = whisperManifestUrl,
            whisperUrl = whisperUrl,
            whisperLocalPath = whisperLocalPath,
            modelPath = modelPath,
            modelUrl = modelUrl,
            tinydiarizeModelPath = tinydiarizeModelPath,
            tinydiarizeModelUrl = tinydiarizeModelUrl,
        )

        installer.run()
    }

    private class ComponentInstaller(
        private val state: ComponentInstallerState,
        private val whisperPath: File,
        private val whisperManifestUrl: String,
        private val whisperUrl: String,
        private val whisperLocalPath: File?,
        private val modelPath: File,
        private val modelUrl: String,
        private val tinydiarizeModelPath: File,
        private val tinydiarizeModelUrl: String,
    ) {
        private val status = ComponentStatusStore(state.componentsFile)
        private val logger = ComponentLogger()

        fun run() {
            state.ensure()
            markPrepareStarted()

            val failures = mutableListOf<String>()

            try {
                installWhisperCli()
            } catch (e: Exception) {
                val message = e.localizedMessage ?: e.javaClass.simpleName
                logger.log("whisper_cli failed: $message")
                status.set("component.whisper_cli.status", "failed")
                status.set("component.whisper_cli.progress", "0")
                status.set("component.whisper_cli.error", message)
                failures += "whisper.cpp-cli"
            }

            try {
                installRemoteFile(
                    componentKey = "base_model",
                    label = "base_model",
                    sourceUrl = modelUrl,
                    destination = modelPath,
                    sourceKind = "url",
                    sourceDetail = modelUrl,
                    extractor = null,
                )
            } catch (e: Exception) {
                val message = e.localizedMessage ?: e.javaClass.simpleName
                logger.log("base_model failed: $message")
                status.set("component.base_model.status", "failed")
                status.set("component.base_model.progress", "0")
                status.set("component.base_model.error", message)
                failures += "base-model"
            }

            try {
                installRemoteFile(
                    componentKey = "tinydiarize_model",
                    label = "tinydiarize_model",
                    sourceUrl = tinydiarizeModelUrl,
                    destination = tinydiarizeModelPath,
                    sourceKind = "url",
                    sourceDetail = tinydiarizeModelUrl,
                    extractor = null,
                )
            } catch (e: Exception) {
                val message = e.localizedMessage ?: e.javaClass.simpleName
                logger.log("tinydiarize_model failed: $message")
                status.set("component.tinydiarize_model.status", "failed")
                status.set("component.tinydiarize_model.progress", "0")
                status.set("component.tinydiarize_model.error", message)
                failures += "tinydiarize-model"
            }

            if (failures.isEmpty()) {
                status.set("transcriber.components.state", "ready")
                status.set("transcriber.components.error", "")
                status.set("transcriber.components.running", "0")
                status.set("transcriber.components.completed_at", timestamp())
                logger.log("prepare completed")
            } else {
                status.set("transcriber.components.state", "failed")
                status.set("transcriber.components.error", "Failed: ${failures.joinToString(" ")}")
                status.set("transcriber.components.running", "0")
                status.set("transcriber.components.completed_at", timestamp())
                logger.log("prepare failed: ${failures.joinToString(" ")}")
                throw IOException("Failed: ${failures.joinToString(", ")}")
            }
        }

        private fun markPrepareStarted() {
            status.setAll(
                mapOf(
                    "transcriber.components.state" to "running",
                    "transcriber.components.running" to "1",
                    "transcriber.components.error" to "",
                    "transcriber.components.started_at" to timestamp(),
                    "transcriber.components.completed_at" to "",
                    "transcriber.components.log" to state.logFile.absolutePath,
                ),
            )
            logger.log("prepare started at ${timestamp()}")
        }

        private fun installWhisperCli() {
            val abi = detectAndroidAbi()
            status.set("component.whisper_cli.abi", abi)
            status.set("component.whisper_cli.manifest_url", whisperManifestUrl)
            status.set("component.whisper_cli.local_path", whisperLocalPath?.absolutePath.orEmpty())
            status.set("component.whisper_cli.path", whisperPath.absolutePath)

            val source = resolveWhisperSource(abi)
            status.set("component.whisper_cli.source_kind", source.kind)
            status.set("component.whisper_cli.source_detail", source.detail)
            status.set("component.whisper_cli.url", source.url ?: "")
            status.set("component.whisper_cli.sha256", source.sha256 ?: "")
            status.set("component.whisper_cli.bytes_total", source.bytesTotal?.toString().orEmpty())
            source.build?.let { status.set("component.whisper_cli.build", it) }
            source.ref?.let { status.set("component.whisper_cli.whisper_ref", it) }
            source.commit?.let { status.set("component.whisper_cli.whisper_commit", it) }

            when (source.kind) {
                "local" -> installLocalFile(
                    componentKey = "whisper_cli",
                    label = "whisper_cli",
                    sourceFile = source.localFile ?: throw IOException("Local whisper package is missing"),
                    destination = whisperPath,
                    extractor = { input, output ->
                        if (source.localFile.extension.equals("zip", ignoreCase = true) || isZipFile(source.localFile)) {
                            extractWhisperCliZip(input, output)
                        } else {
                            copyFile(source.localFile, output)
                        }
                    },
                )

                "url", "manifest" -> installRemoteFile(
                    componentKey = "whisper_cli",
                    label = "whisper_cli",
                    sourceUrl = source.url ?: throw IOException("Resolved whisper download URL is empty"),
                    destination = whisperPath,
                    sourceKind = source.kind,
                    sourceDetail = source.detail,
                    expectedSha256 = source.sha256,
                    extractor = { input, output ->
                        if (source.url.endsWith(".zip", ignoreCase = true)) {
                            extractWhisperCliZip(input, output)
                        } else {
                            copyFile(input, output)
                        }
                    },
                )

                else -> throw IOException("Unsupported whisper source kind: ${source.kind}")
            }

            verifyWhisperExecutable(whisperPath)
        }

        private fun resolveWhisperSource(abi: String): WhisperSource {
            whisperLocalPath?.let { localFile ->
                if (!localFile.isFile) {
                    throw IOException("Selected local whisper package is missing: ${localFile.absolutePath}")
                }
                return WhisperSource(
                    kind = "local",
                    detail = localFile.absolutePath,
                    localFile = localFile,
                    bytesTotal = localFile.length(),
                )
            }

            if (whisperUrl.isNotBlank()) {
                return WhisperSource(
                    kind = "url",
                    detail = whisperUrl,
                    url = whisperUrl,
                )
            }

            if (abi == "unknown") {
                throw IOException("Unable to detect Android CPU ABI")
            }

            status.set("component.whisper_cli.status", "resolving")
            logger.log("downloading whisper manifest from $whisperManifestUrl")
            val manifestText = downloadText(whisperManifestUrl)
            state.manifestCacheFile.parentFile?.mkdirs()
            state.manifestCacheFile.writeText(manifestText)
            val manifest = parseEnvManifest(manifestText)
            val resolvedUrl = manifest["abi.$abi.url"].orEmpty()

            if (resolvedUrl.isBlank()) {
                throw IOException("No whisper-cli package published for ABI $abi")
            }

            return WhisperSource(
                kind = "manifest",
                detail = resolvedUrl,
                url = resolvedUrl,
                sha256 = manifest["abi.$abi.sha256"]?.ifBlank { null },
                bytesTotal = manifest["abi.$abi.size"]?.toLongOrNull(),
                build = manifest["build"]?.ifBlank { null },
                ref = manifest["whisper_cpp_ref"]?.ifBlank { null },
                commit = manifest["whisper_cpp_commit"]?.ifBlank { null },
            )
        }

        private fun installLocalFile(
            componentKey: String,
            label: String,
            sourceFile: File,
            destination: File,
            extractor: ((File, File) -> Unit)?,
        ) {
            if (componentKey != "whisper_cli" && destination.isFile) {
                markReady(componentKey, destination)
                logger.log("$label already present at ${destination.absolutePath}")
                return
            }
            if (componentKey == "whisper_cli" && destination.isFile && verifyWhisperExecutable(destination, throwOnFailure = false)) {
                markReady(componentKey, destination)
                logger.log("$label already present at ${destination.absolutePath}")
                return
            }

            destination.parentFile?.mkdirs()
            val tmp = File(destination.parentFile, "${destination.name}.download")
            val prepared = File(destination.parentFile, "${destination.name}.prepared")
            tmp.delete()
            prepared.delete()

            status.set("component.$componentKey.status", "installing")
            status.set("component.$componentKey.progress", "0")
            status.set("component.$componentKey.error", "")
            status.set("component.$componentKey.bytes_total", sourceFile.length().toString())
            logger.log("installing $label from local package ${sourceFile.absolutePath}")

            copyWithProgress(
                input = sourceFile.inputStream().buffered(),
                output = tmp.outputStream().buffered(),
                totalBytes = sourceFile.length().takeIf { it > 0 },
                onProgress = { downloaded, total ->
                    updateProgress(componentKey, downloaded, total)
                },
            )

            if (extractor != null) {
                extractor(tmp, prepared)
                tmp.delete()
                prepared.renameTo(destination)
            } else {
                tmp.renameTo(destination)
            }

            if (componentKey == "whisper_cli") {
                destination.setExecutable(true, false)
            }

            markReady(componentKey, destination)
            logger.log("$label ready at ${destination.absolutePath}")
        }

        private fun installRemoteFile(
            componentKey: String,
            label: String,
            sourceUrl: String,
            destination: File,
            sourceKind: String,
            sourceDetail: String,
            expectedSha256: String? = null,
            extractor: ((File, File) -> Unit)?,
        ) {
            if (sourceUrl.isBlank()) {
                throw IOException("No download source configured")
            }

            if (componentKey != "whisper_cli" && destination.isFile) {
                markReady(componentKey, destination)
                logger.log("$label already present at ${destination.absolutePath}")
                return
            }
            if (componentKey == "whisper_cli" && destination.isFile && verifyWhisperExecutable(destination, throwOnFailure = false)) {
                markReady(componentKey, destination)
                logger.log("$label already present at ${destination.absolutePath}")
                return
            }

            destination.parentFile?.mkdirs()
            val tmp = File(destination.parentFile, "${destination.name}.download")
            val prepared = File(destination.parentFile, "${destination.name}.prepared")
            tmp.delete()
            prepared.delete()

            status.set("component.$componentKey.source_kind", sourceKind)
            status.set("component.$componentKey.source_detail", sourceDetail)
            status.set("component.$componentKey.url", sourceUrl)
            status.set("component.$componentKey.path", destination.absolutePath)
            status.set("component.$componentKey.status", "downloading")
            status.set("component.$componentKey.progress", "0")
            status.set("component.$componentKey.bytes_downloaded", "0")
            status.set("component.$componentKey.error", "")
            logger.log("downloading $label from $sourceUrl")

            val downloadResult = downloadToFile(
                componentKey = componentKey,
                url = sourceUrl,
                destination = tmp,
            )

            if (!expectedSha256.isNullOrBlank()) {
                status.set("component.$componentKey.status", "verifying")
                val actual = sha256Hex(tmp)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    tmp.delete()
                    throw IOException("SHA-256 verification failed: expected ${expectedSha256.lowercase(Locale.ROOT)}, got $actual")
                }
            }

            if (extractor != null) {
                extractor(tmp, prepared)
                tmp.delete()
                prepared.renameTo(destination)
            } else {
                tmp.renameTo(destination)
            }

            if (componentKey == "whisper_cli") {
                destination.setExecutable(true, false)
            }

            markReady(componentKey, destination, bytesDownloadedOverride = downloadResult.bytesDownloaded)
            logger.log("$label ready at ${destination.absolutePath}")
        }

        private fun markReady(componentKey: String, destination: File, bytesDownloadedOverride: Long? = null) {
            status.setAll(
                mapOf(
                    "component.$componentKey.status" to "ready",
                    "component.$componentKey.progress" to "100",
                    "component.$componentKey.bytes_downloaded" to (bytesDownloadedOverride ?: destination.length()).toString(),
                    "component.$componentKey.bytes_total" to destination.length().toString(),
                    "component.$componentKey.error" to "",
                ),
            )
        }

        private fun updateProgress(componentKey: String, downloaded: Long, total: Long?) {
            val progress = when {
                total == null || total <= 0L -> 0
                downloaded >= total -> 100
                else -> ((downloaded * 100L) / total).toInt().coerceIn(0, 99)
            }
            status.set("component.$componentKey.bytes_downloaded", downloaded.toString())
            total?.let { status.set("component.$componentKey.bytes_total", it.toString()) }
            status.set("component.$componentKey.progress", progress.toString())
        }

        private fun downloadText(url: String): String {
            val connection = openConnection(url)
            return connection.useAndDisconnect {
                val code = responseCode
                val body = when {
                    code in 200..299 -> inputStream.bufferedReader().use { it.readText() }
                    else -> errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }
                if (code !in 200..299) {
                    throw IOException(httpErrorMessage(responseCode, responseMessage, body))
                }
                body
            }
        }

        private fun downloadToFile(componentKey: String, url: String, destination: File): DownloadResult {
            val connection = openConnection(url)
            return connection.useAndDisconnect {
                val code = responseCode
                val length = contentLengthLong.takeIf { it > 0L }
                if (length != null) {
                    status.set("component.$componentKey.bytes_total", length.toString())
                }
                if (code !in 200..299) {
                    val errorBody = errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IOException(httpErrorMessage(code, responseMessage, errorBody))
                }

                var lastReported = 0L
                var downloaded = 0L
                BufferedInputStream(inputStream).use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) {
                                break
                            }
                            output.write(buffer, 0, read)
                            downloaded += read.toLong()
                            val shouldReport = downloaded == length || downloaded - lastReported >= PROGRESS_UPDATE_BYTES
                            if (shouldReport) {
                                updateProgress(componentKey, downloaded, length)
                                lastReported = downloaded
                            }
                        }
                    }
                }

                updateProgress(componentKey, downloaded, length ?: downloaded)
                DownloadResult(bytesDownloaded = downloaded, bytesTotal = length ?: downloaded)
            }
        }

        private fun openConnection(url: String): HttpURLConnection {
            var currentUrl = URL(url)
            repeat(HTTP_REDIRECT_LIMIT) {
                val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                    readTimeout = HTTP_READ_TIMEOUT_MS
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", HELPER_USER_AGENT)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                }

                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308,
                    -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Redirected without Location header from $currentUrl")
                        connection.disconnect()
                        currentUrl = URL(currentUrl, location)
                    }

                    else -> return connection
                }
            }

            throw IOException("Too many redirects while downloading $url")
        }

        private fun httpErrorMessage(code: Int, responseMessage: String?, body: String): String {
            val snippet = body
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(240)
            return if (snippet.isNotEmpty()) {
                "HTTP $code ${responseMessage.orEmpty()}: $snippet"
            } else {
                "HTTP $code ${responseMessage.orEmpty()}".trim()
            }
        }

        private fun detectAndroidAbi(): String {
            val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
            return when {
                "arm64-v8a" in abis -> "arm64-v8a"
                "armeabi-v7a" in abis -> "armeabi-v7a"
                "x86_64" in abis -> "x86_64"
                abis.isNotEmpty() -> abis.first()
                else -> "unknown"
            }
        }

        private fun parseEnvManifest(text: String): Map<String, String> {
            val values = mutableMapOf<String, String>()
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
                .forEach { line ->
                    val split = line.indexOf('=')
                    values[line.substring(0, split)] = line.substring(split + 1)
                }
            return values
        }

        private fun copyFile(source: File, destination: File) {
            source.inputStream().buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
        }

        private fun copyWithProgress(
            input: java.io.InputStream,
            output: java.io.OutputStream,
            totalBytes: Long?,
            onProgress: (downloaded: Long, total: Long?) -> Unit,
        ) {
            input.use { source ->
                output.use { sink ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var downloaded = 0L
                    var lastReported = 0L

                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) {
                            break
                        }
                        sink.write(buffer, 0, read)
                        downloaded += read.toLong()
                        if (downloaded == totalBytes || downloaded - lastReported >= PROGRESS_UPDATE_BYTES) {
                            onProgress(downloaded, totalBytes)
                            lastReported = downloaded
                        }
                    }

                    onProgress(downloaded, totalBytes ?: downloaded)
                }
            }
        }

        private fun extractWhisperCliZip(zipFile: File, destination: File) {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && (name == "whisper-cli" || name == "whisper-cli.exe")) {
                        destination.outputStream().buffered().use { output ->
                            zip.copyTo(output)
                        }
                        zip.closeEntry()
                        destination.setExecutable(true, false)
                        return
                    }
                    zip.closeEntry()
                }
            }

            throw IOException("Downloaded package did not contain whisper-cli")
        }

        private fun isZipFile(file: File): Boolean {
            if (!file.isFile || file.length() < 4L) {
                return false
            }
            file.inputStream().use { input ->
                val signature = ByteArray(4)
                if (input.read(signature) != signature.size) {
                    return false
                }
                return signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte()
            }
        }

        private fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun verifyWhisperExecutable(file: File, throwOnFailure: Boolean = true): Boolean {
            file.setExecutable(true, false)
            if (!file.isFile || !file.canExecute()) {
                if (throwOnFailure) {
                    throw IOException("whisper-cli is not executable on this device")
                }
                return false
            }

            val helpResult = ShellCommandRunner.run(file.absolutePath, "-h", timeoutMs = 15_000)
            val combined = buildString {
                append(helpResult.stdout)
                append('\n')
                append(helpResult.stderr)
            }
            val looksValid = helpResult.exitCode == 0 || Regex("usage|whisper|options", RegexOption.IGNORE_CASE).containsMatchIn(combined)
            if (!looksValid) {
                if (throwOnFailure) {
                    throw IOException(
                        "Downloaded whisper-cli could not execute on this device: " +
                            combined.replace(Regex("\\s+"), " ").trim().take(240),
                    )
                }
                return false
            }

            return true
        }

        private fun timestamp(): String = Instant.now().toString()
    }

    private class ComponentInstallerState(moduleDir: File) {
        val stateDir = File(moduleDir, ".state")
        val componentsFile = File(stateDir, "transcriber-components.env")
        val manifestCacheFile = File(stateDir, "transcriber-tools.env")
        val logFile = File(stateDir, "transcriber.log")

        fun ensure() {
            stateDir.mkdirs()
            componentsFile.parentFile?.mkdirs()
            if (!componentsFile.exists()) {
                componentsFile.writeText("")
            }
        }
    }

    private class ComponentStatusStore(private val file: File) {
        @Synchronized
        fun set(key: String, value: String) {
            val values = load()
            values[key] = sanitize(value)
            save(values)
        }

        @Synchronized
        fun setAll(entries: Map<String, String>) {
            val values = load()
            for ((key, value) in entries) {
                values[key] = sanitize(value)
            }
            save(values)
        }

        private fun load(): LinkedHashMap<String, String> {
            val values = LinkedHashMap<String, String>()
            if (!file.exists()) {
                return values
            }

            file.forEachLine { line ->
                val split = line.indexOf('=')
                if (split > 0) {
                    values[line.substring(0, split)] = line.substring(split + 1)
                }
            }
            return values
        }

        private fun save(values: LinkedHashMap<String, String>) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.bufferedWriter().use { writer ->
                values.forEach { (key, value) ->
                    writer.append(key)
                    writer.append('=')
                    writer.append(value)
                    writer.append('\n')
                }
            }
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }

        private fun sanitize(value: String): String = value.replace(Regex("[\\r\\n]+"), " ").trim()
    }

    private class ComponentLogger {
        fun log(message: String) {
            println("[transcriber-components] $message")
        }
    }

    private data class WhisperSource(
        val kind: String,
        val detail: String,
        val url: String? = null,
        val localFile: File? = null,
        val sha256: String? = null,
        val bytesTotal: Long? = null,
        val build: String? = null,
        val ref: String? = null,
        val commit: String? = null,
    )

    private data class DownloadResult(
        val bytesDownloaded: Long,
        val bytesTotal: Long,
    )

    private inline fun <T> HttpURLConnection.useAndDisconnect(block: HttpURLConnection.() -> T): T =
        try {
            block()
        } finally {
            disconnect()
        }
}
