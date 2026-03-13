/*
 * SPDX-FileCopyrightText: 2026 wjdob
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.headless

import java.util.concurrent.TimeUnit

data class ShellCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

object ShellCommandRunner {
    fun run(
        vararg args: String,
        timeoutMs: Long = 5_000,
    ): ShellCommandResult {
        val process = ProcessBuilder(*args)
            .redirectErrorStream(false)
            .start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Timed out while running: ${args.joinToString(" ")}",
            )
        }

        val stdout = process.inputStream.bufferedReader().use { it.readText().trim() }
        val stderr = process.errorStream.bufferedReader().use { it.readText().trim() }

        return ShellCommandResult(
            exitCode = process.exitValue(),
            stdout = stdout,
            stderr = stderr,
        )
    }
}
