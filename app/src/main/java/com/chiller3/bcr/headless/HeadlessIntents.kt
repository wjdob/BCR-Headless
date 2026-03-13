/*
 * SPDX-FileCopyrightText: 2026 wjdob
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.headless

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

object HeadlessIntents {
    data class OpenTarget(
        val uri: Uri,
        val mimeType: String,
    )

    private const val DOCUMENTSUI_AUTHORITY = "com.android.externalstorage.documents"
    private val PRIMARY_EXTERNAL_PREFIXES = listOf(
        "/storage/emulated/0",
        "/sdcard",
        "/mnt/sdcard",
        "/storage/self/primary",
    )

    fun createOpenOutputDirTarget(outputDir: File): OpenTarget {
        val canonicalOutputDir = outputDir.canonicalFile

        // Do not ask StorageManager for the "real" external directory here.
        // Some ROMs reject that framework path from the root-side helper with a
        // callingPackage/UID mismatch, which would block recorder startup.
        val relative = resolvePrimaryExternalRelativePath(canonicalOutputDir)

        // Reuse the same DocumentsUI directory-view trick as the original app
        // when the output path lives under shared storage. That gives the user
        // a better handoff than a plain file:// URI for a directory.
        val uri = if (relative != null) {
            DocumentsContract.buildDocumentUri(
                DOCUMENTSUI_AUTHORITY,
                if (relative.isEmpty()) "primary:" else "primary:$relative",
            )
        } else {
            Uri.fromFile(canonicalOutputDir)
        }

        return OpenTarget(
            uri = uri,
            mimeType = if (uri.scheme == "content") "vnd.android.document/directory" else "*/*",
        )
    }

    fun createOpenRecordingTarget(file: File): OpenTarget {
        val canonicalFile = file.canonicalFile
        val relative = resolvePrimaryExternalRelativePath(canonicalFile)
        val uri = if (relative != null) {
            DocumentsContract.buildDocumentUri(
                DOCUMENTSUI_AUTHORITY,
                "primary:$relative",
            )
        } else {
            Uri.fromFile(canonicalFile)
        }

        return OpenTarget(
            uri = uri,
            mimeType = when (canonicalFile.extension.lowercase()) {
                "wav" -> "audio/wav"
                else -> "audio/*"
            },
        )
    }

    fun createOpenOutputDirIntent(outputDir: File): Intent {
        val target = createOpenOutputDirTarget(outputDir)

        return Intent(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setDataAndType(
                target.uri,
                target.mimeType,
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (target.uri.scheme == "content") {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
        }
    }

    private fun resolvePrimaryExternalRelativePath(outputDir: File): String? {
        val normalizedPath = outputDir.path.replace(File.separatorChar, '/')

        for (prefix in PRIMARY_EXTERNAL_PREFIXES) {
            val normalizedPrefix = prefix.trimEnd('/')

            if (normalizedPath == normalizedPrefix) {
                return ""
            }

            val prefixWithSlash = "$normalizedPrefix/"
            if (normalizedPath.startsWith(prefixWithSlash)) {
                return normalizedPath.removePrefix(prefixWithSlash)
            }
        }

        return null
    }
}
