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

@Serializable
data class RecordingLogEntry(
    val id: String,
    val timestamp: String,
    val direction: String?,
    val phoneNumber: String?,
    val status: String,
    val durationSeconds: Double?,
    val outputFile: String?,
    val error: String?,
)

class RecordingLogStore(
    private val file: File,
) {
    fun ensureExists() {
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.writeText("[]\n")
        }
    }

    @Synchronized
    fun append(entry: RecordingLogEntry) {
        ensureExists()

        val entries = load().toMutableList()
        entries.add(0, entry)

        // Keep the on-device history bounded so the module does not grow
        // without limit on long-running installs.
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(entries.lastIndex)
        }

        save(entries)
    }

    @Synchronized
    fun clear() {
        ensureExists()
        save(emptyList())
    }

    @Synchronized
    fun toJson(): String {
        ensureExists()
        return JSON.encodeToString(load())
    }

    private fun load(): List<RecordingLogEntry> {
        if (!file.exists()) {
            return emptyList()
        }

        return try {
            JSON.decodeFromString(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(entries: List<RecordingLogEntry>) {
        file.writeText(JSON.encodeToString(entries) + "\n")
    }

    companion object {
        private const val MAX_ENTRIES = 250
        private val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
}
