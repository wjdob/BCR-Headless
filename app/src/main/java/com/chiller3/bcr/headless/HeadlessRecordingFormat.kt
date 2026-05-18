/*
 * SPDX-FileCopyrightText: 2026 wjdob
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.headless

import com.chiller3.bcr.format.AacFormat
import com.chiller3.bcr.format.Format
import com.chiller3.bcr.format.OpusFormat
import com.chiller3.bcr.format.WaveFormat

enum class HeadlessRecordingFormat(
    val configValue: String,
    val label: String,
    val extension: String,
    private val factory: () -> Format,
) {
    Wave(
        configValue = "wav",
        label = "WAV/PCM",
        extension = "wav",
        factory = { WaveFormat },
    ),
    Opus(
        configValue = "opus",
        label = "OGG/Opus",
        extension = "ogg",
        factory = { OpusFormat() },
    ),
    Aac(
        configValue = "aac",
        label = "M4A/AAC",
        extension = "m4a",
        factory = { AacFormat() },
    ),
    ;

    fun newFormat(): Format = factory()

    fun isAvailable(): Boolean = runCatching { newFormat() }.isSuccess

    companion object {
        fun available(): List<HeadlessRecordingFormat> = entries.filter { it.isAvailable() }

        fun availableConfigValues(): List<String> = available().map { it.configValue }

        fun fromConfigValue(value: String?): HeadlessRecordingFormat {
            val normalized = value?.trim()?.lowercase()
            return available().firstOrNull { it.configValue == normalized } ?: Wave
        }
    }
}
