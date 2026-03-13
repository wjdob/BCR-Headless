/*
 * SPDX-FileCopyrightText: 2026 wjdob
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.headless

import android.content.Context
import android.provider.CallLog
import com.chiller3.bcr.output.CallDirection
import com.chiller3.bcr.output.PhoneNumber
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.time.ZonedDateTime

data class ResolvedPhoneNumber(
    val raw: String,
    val display: String,
    val filenameComponent: String,
)

object HeadlessCallLogResolver {
    fun resolveRecentNumber(
        context: Context,
        direction: CallDirection?,
        startedAt: ZonedDateTime?,
        fallbackRawNumber: String? = null,
    ): ResolvedPhoneNumber? {
        val resolvedFromCallLog = if (startedAt != null) {
            queryRecentCallLog(context, direction, startedAt)
        } else {
            null
        }

        if (resolvedFromCallLog != null) {
            return resolvedFromCallLog
        }

        val resolvedFromShell = if (startedAt != null) {
            queryRecentCallLogViaShell(context, direction, startedAt)
        } else {
            null
        }

        if (resolvedFromShell != null) {
            return resolvedFromShell
        }

        return fallbackRawNumber?.let { createResolvedNumber(context, it) }
    }

    private fun queryRecentCallLog(
        context: Context,
        direction: CallDirection?,
        startedAt: ZonedDateTime,
    ): ResolvedPhoneNumber? {
        val startedAtMillis = startedAt.toInstant().toEpochMilli()
        val selectionParts = mutableListOf(
            "${CallLog.Calls.DATE} >= ?",
            "${CallLog.Calls.DATE} <= ?",
        )
        val selectionArgs = mutableListOf(
            (startedAtMillis - WINDOW_BEFORE_MS).toString(),
            (startedAtMillis + WINDOW_AFTER_MS).toString(),
        )

        val expectedType = when (direction) {
            CallDirection.IN -> CallLog.Calls.INCOMING_TYPE
            CallDirection.OUT -> CallLog.Calls.OUTGOING_TYPE
            else -> null
        }
        if (expectedType != null) {
            selectionParts.add("${CallLog.Calls.TYPE} = ?")
            selectionArgs.add(expectedType.toString())
        }

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI.buildUpon()
                        .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "5")
                        .build(),
                    arrayOf(CallLog.Calls.NUMBER),
                    selectionParts.joinToString(" AND "),
                    selectionArgs.toTypedArray(),
                    "${CallLog.Calls.DATE} DESC",
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val rawNumber = cursor.getString(0)?.trim().orEmpty()
                        if (rawNumber.isBlank()) {
                            continue
                        }

                        val resolved = createResolvedNumber(context, rawNumber)
                        if (resolved != null) {
                            return resolved
                        }
                    }
                }
            } catch (_: Exception) {
                return null
            }

            if (attempt < MAX_ATTEMPTS - 1) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }

        return null
    }

    private fun queryRecentCallLogViaShell(
        context: Context,
        direction: CallDirection?,
        startedAt: ZonedDateTime,
    ): ResolvedPhoneNumber? {
        val startedAtMillis = startedAt.toInstant().toEpochMilli()
        val expectedType = when (direction) {
            CallDirection.IN -> CallLog.Calls.INCOMING_TYPE
            CallDirection.OUT -> CallLog.Calls.OUTGOING_TYPE
            else -> null
        }
        val where = buildString {
            append("date >= ")
            append(startedAtMillis - WINDOW_BEFORE_MS)
            append(" AND date <= ")
            append(startedAtMillis + WINDOW_AFTER_MS)

            if (expectedType != null) {
                append(" AND type = ")
                append(expectedType)
            }
        }

        repeat(MAX_ATTEMPTS) { attempt ->
            // Query the narrow time window first, then fall back to the newest
            // rows for the matching direction. Some ROMs delay call-log writes,
            // so a relaxed second pass is more reliable than giving up and
            // labelling the recording as "Unknown number".
            queryShellRows(where = where, limit = 5)
                ?.let { rows -> firstResolvedNumber(context, rows) }
                ?.let { return it }

            queryShellRows(
                where = expectedType?.let { "type = $it" },
                limit = 8,
            )?.let { rows ->
                firstResolvedNumber(context, rows)?.let { return it }
            }

            if (attempt < MAX_ATTEMPTS - 1) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }

        return null
    }

    private fun queryShellRows(
        where: String?,
        limit: Int,
    ): List<Map<String, String>>? {
        val args = mutableListOf(
            "content",
            "query",
            "--uri",
            "${CallLog.Calls.CONTENT_URI}?limit=$limit",
            "--projection",
            "number:normalized_number:via_number:type:date",
            "--sort",
            "date DESC",
        )

        if (!where.isNullOrBlank()) {
            args += listOf("--where", where)
        }

        val result = try {
            ShellCommandRunner.run(*args.toTypedArray(), timeoutMs = 7_500)
        } catch (_: Exception) {
            return null
        }

        return if (result.exitCode == 0) parseShellRows(result.stdout) else null
    }

    private fun firstResolvedNumber(
        context: Context,
        rows: List<Map<String, String>>,
    ): ResolvedPhoneNumber? {
        for (row in rows) {
            for (key in SHELL_NUMBER_KEYS) {
                normalizeCandidate(row[key])
                    ?.let { rawNumber -> createResolvedNumber(context, rawNumber) }
                    ?.let { return it }
            }
        }

        return null
    }

    private fun parseShellRows(output: String): List<Map<String, String>> {
        val rows = mutableListOf<Map<String, String>>()

        for (line in output.lineSequence()) {
            if (!line.startsWith("Row:")) {
                continue
            }

            val payload = line.substringAfter(' ').substringAfter(' ')
            val map = linkedMapOf<String, String>()

            for (part in payload.split(", ")) {
                val separator = part.indexOf('=')
                if (separator <= 0) {
                    continue
                }

                val key = part.substring(0, separator).trim()
                val value = part.substring(separator + 1).trim()
                map[key] = value
            }

            if (map.isNotEmpty()) {
                rows.add(map)
            }
        }

        return rows
    }

    private fun createResolvedNumber(context: Context, rawNumber: String): ResolvedPhoneNumber? {
        val cleaned = normalizeCandidate(rawNumber) ?: return null
        if (cleaned.isEmpty()) {
            return null
        }

        val display = try {
            val number = PhoneNumber(cleaned)
            number.format(context, PhoneNumberUtil.PhoneNumberFormat.E164) ?: number.toString()
        } catch (_: Exception) {
            cleaned
        }

        val filenameComponent = sanitizeForFilename(display)
        if (filenameComponent.isEmpty()) {
            return null
        }

        return ResolvedPhoneNumber(
            raw = cleaned,
            display = display,
            filenameComponent = filenameComponent,
        )
    }

    private fun normalizeCandidate(rawNumber: String?): String? {
        val cleaned = rawNumber?.trim().orEmpty()
        if (cleaned.isEmpty() || cleaned.equals("null", ignoreCase = true)) {
            return null
        }

        if (cleaned.count(Char::isDigit) < 2) {
            return null
        }

        return cleaned
    }

    private fun sanitizeForFilename(value: String): String {
        val sanitized = buildString {
            for (c in value) {
                when {
                    c.isLetterOrDigit() -> append(c)
                    c == '+' || c == '-' || c == '_' -> append(c)
                }
            }
        }.trim('_')

        return sanitized.take(MAX_FILENAME_COMPONENT_LENGTH)
    }

    private const val WINDOW_BEFORE_MS = 2 * 60 * 1_000L
    private const val WINDOW_AFTER_MS = 10 * 60 * 1_000L
    private const val RETRY_DELAY_MS = 1_000L
    private const val MAX_ATTEMPTS = 12
    private const val MAX_FILENAME_COMPONENT_LENGTH = 32
    private val SHELL_NUMBER_KEYS = listOf("number", "normalized_number", "via_number")
}
