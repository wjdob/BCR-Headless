/*
 * SPDX-FileCopyrightText: 2026 wjdob
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.headless

object HeadlessActiveCallNumberResolver {
    fun resolveCurrentNumber(): String? {
        // `dumpsys telecom` is available to the shell on a wide range of ROMs
        // and gives us a number source even when the headless process cannot
        // register framework listeners with a normal app identity.
        val result = runCatching {
            ShellCommandRunner.run("dumpsys", "telecom", timeoutMs = 7_500)
        }.getOrNull() ?: return null

        if (result.exitCode != 0) {
            return null
        }

        return TELECOM_URI_REGEX.findAll(result.stdout)
            .map { it.groupValues[1] }
            .mapNotNull(::sanitizeNumber)
            .firstOrNull()
    }

    private fun sanitizeNumber(raw: String): String? {
        val cleaned = buildString(raw.length) {
            for (c in raw.trim()) {
                if (c.isDigit() || c == '+' || c == '*' || c == '#') {
                    append(c)
                }
            }
        }

        return cleaned.takeIf { it.count(Char::isDigit) >= 3 }
    }

    private val TELECOM_URI_REGEX =
        Regex("""\btel:([+*#0-9().\-\s]{3,})""", RegexOption.IGNORE_CASE)
}
