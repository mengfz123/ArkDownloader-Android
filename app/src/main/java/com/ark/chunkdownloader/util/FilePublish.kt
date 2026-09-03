package com.ark.chunkdownloader.util

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object FilePublish {
    fun defaultDownloadDir(context: Context): File {
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(public, "ArkDownloads").apply { mkdirs() }
    }

    fun stagingDir(context: Context): File =
        File(context.getExternalFilesDir(null), "ArkChunkParts").apply { mkdirs() }

    /** Prefer [preferred] if writable; otherwise public Downloads/ArkDownloads. */
    fun resolveWritableDir(context: Context, preferred: String?): File {
        if (!preferred.isNullOrBlank()) {
            val dir = File(preferred)
            if ((dir.exists() || dir.mkdirs()) && dir.canWrite()) return dir
        }
        return defaultDownloadDir(context)
    }

    /**
     * Publish [source] into Downloads/ArkDownloads (or [subDir]).
     * Prefers atomic move; falls back to copy. Scans once — no MediaStore double-write.
     */
    fun publishToDownloads(context: Context, source: File, displayName: String, subDir: String?): File {
        val targetDir = if (!subDir.isNullOrBlank()) {
            File(defaultDownloadDir(context), subDir.trim('/')).apply { mkdirs() }
        } else {
            defaultDownloadDir(context)
        }
        val dest = File(targetDir, displayName)
        moveOrCopy(source, dest)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(dest.absolutePath),
            arrayOf("application/octet-stream"),
            null
        )
        return dest
    }

    /** Move when same volume; otherwise copy+delete. */
    fun moveOrCopy(source: File, dest: File) {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        if (source.renameTo(dest)) return
        try {
            Files.move(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (_: Exception) {
            // cross-device — copy
        }
        source.inputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output, 512 * 1024) }
        }
        source.delete()
    }
}
