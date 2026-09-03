package com.ark.chunkdownloader.util

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File

object FilePublish {
    fun defaultDownloadDir(@Suppress("UNUSED_PARAMETER") context: Context): File {
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(public, "ArkDownloads").apply { mkdirs() }
    }

    fun stagingDir(context: Context): File =
        File(context.getExternalFilesDir(null), "ArkDownloads").apply { mkdirs() }

    fun resolveWritableDir(context: Context, preferred: String?): File {
        val candidates = buildList {
            preferred?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
            add(defaultDownloadDir(context))
            add(stagingDir(context))
        }
        for (dir in candidates) {
            runCatching {
                dir.mkdirs()
                if (dir.isDirectory && dir.canWrite()) {
                    val probe = File(dir, ".ark_write_probe")
                    if (probe.createNewFile() || probe.exists()) {
                        probe.delete()
                        return dir
                    }
                }
            }
        }
        return stagingDir(context).also { it.mkdirs() }
    }

    fun scanFile(context: Context, file: File) {
        if (!file.exists()) return
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("application/octet-stream"),
            null
        )
    }

    fun openFile(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        return runCatching {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, guessMime(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                Intent.createChooser(intent, "打开文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    private fun guessMime(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
                lower.endsWith(".avi") || lower.endsWith(".mov") -> "video/*"
            lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".m4a") ||
                lower.endsWith(".wav") -> "audio/*"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".webp") || lower.endsWith(".gif") -> "image/*"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") -> "application/zip"
            lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".md") -> "text/plain"
            else -> "*/*"
        }
    }
}
