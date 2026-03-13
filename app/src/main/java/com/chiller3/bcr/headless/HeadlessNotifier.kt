/*
 * SPDX-FileCopyrightText: 2026 wjdob
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.headless

import com.chiller3.bcr.output.CallDirection
import java.io.File
import kotlin.math.roundToInt

class HeadlessNotifier(
    private val enabled: Boolean,
) {
    companion object {
        private const val SHELL_NOTIFICATION_TAG = "bcr_headless_recording"
        private const val SHELL_NOTIFICATION_ICON = "@android:drawable/stat_sys_phone_call"
    }

    fun ensureChannels() {
        // Shell-backed notifications create their channel lazily inside
        // `cmd notification post`, so there is nothing to pre-register here.
    }

    fun showRecordingInProgress(outputDir: File, direction: CallDirection?) {
        if (!enabled) {
            return
        }

        ensureChannels()
        val message = buildString {
            append("Saving to ")
            append(outputDir.absolutePath)
            append(" • ")
            append(
                when (direction) {
                    CallDirection.IN -> "incoming call"
                    CallDirection.OUT -> "outgoing call"
                    CallDirection.CONFERENCE -> "conference call"
                    null -> "active call"
                },
            )
        }

        postShellNotification(
            title = "Call recording in progress",
            text = message,
        )
    }

    fun dismissRecordingInProgress() {
        // Shell notifications do not expose a dedicated cancel subcommand.
        // Completion states overwrite the in-progress notification using the
        // same tag, which keeps the UX concise without relying on hidden APIs.
    }

    fun showRecordingSuccess(outputDir: File, outputFile: File, durationSeconds: Double?) {
        if (!enabled) {
            return
        }

        ensureChannels()
        val durationText = durationSeconds?.let { "${(it * 10).roundToInt() / 10.0}s" } ?: "unknown length"
        val message = "${outputFile.name} • $durationText"

        postShellNotification(
            title = "Call recording saved",
            text = "${outputFile.absolutePath}\n$message",
        )
    }

    fun showRecordingFailure(outputDir: File, error: String?) {
        if (!enabled) {
            return
        }

        ensureChannels()
        val message = error?.ifBlank { null } ?: "Unknown error"

        postShellNotification(
            title = "Call recording failed",
            text = "${outputDir.absolutePath}\n$message",
        )
    }

    fun showRecordingDiscarded(outputDir: File, durationSeconds: Double?) {
        if (!enabled) {
            return
        }

        val durationText = durationSeconds?.let { "${(it * 10).roundToInt() / 10.0}s" } ?: "too short"
        postShellNotification(
            title = "Call recording discarded",
            text = "${outputDir.absolutePath}\nDuration: $durationText",
        )
    }

    private fun postShellNotification(title: String, text: String) {
        if (!enabled) {
            return
        }

        notifySafely("post shell notification") {
            var result = ShellCommandRunner.run(
                "cmd",
                "notification",
                "post",
                "-S",
                "bigtext",
                "-i",
                SHELL_NOTIFICATION_ICON,
                "-t",
                title,
                SHELL_NOTIFICATION_TAG,
                text,
            )

            if (result.exitCode != 0) {
                // Older platform builds can be pickier about advanced shell
                // notification options. Retry with the minimal form before
                // giving up so the feature stays best-effort across ROMs.
                result = ShellCommandRunner.run(
                    "cmd",
                    "notification",
                    "post",
                    "-t",
                    title,
                    SHELL_NOTIFICATION_TAG,
                    text,
                )
            }

            if (result.exitCode != 0) {
                throw IllegalStateException(
                    result.stderr.ifBlank { result.stdout }.ifBlank { "cmd notification failed" },
                )
            }
        }
    }

    private fun notifySafely(action: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Notifications are convenience UX only. They must never be able to
            // interrupt the recorder pipeline or crash a poller thread.
            System.err.println(
                "Notification operation failed during $action: " +
                    (e.localizedMessage ?: e.javaClass.simpleName),
            )
        }
    }
}
