/*
 * SPDX-FileCopyrightText: 2026 wjdob
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.headless

import java.io.File
import java.util.Locale

class ModuleConfigStore(
    private val configDir: File,
) {
    fun get(key: String): String? {
        val file = File(configDir, key)
        if (!file.isFile) {
            return null
        }

        return file.readText()
            .replace(Regex("[\\r\\n]+\$"), "")
    }

    fun getOrDefault(key: String, defaultValue: String): String =
        get(key)?.takeIf { it.isNotEmpty() } ?: defaultValue

    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        when (get(key)?.trim()?.lowercase(Locale.ROOT)) {
            null, "" -> defaultValue
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> defaultValue
        }

    fun getInt(key: String, defaultValue: Int): Int =
        get(key)?.trim()?.toIntOrNull() ?: defaultValue
}
