package com.ark.chunkdownloader.util

import org.json.JSONObject

object FormatUtil {
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes} B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

    fun formatEta(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) return "—"
        val s = seconds.toLong()
        if (s < 60) return "${s}s"
        val m = s / 60
        if (m < 60) return "${m}m ${s % 60}s"
        val h = m / 60
        return "${h}h ${m % 60}m"
    }

    fun inferFileName(url: String): String = UrlResolve.inferFileName(url)

    fun parseHeaders(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap {
                obj.keys().forEach { key ->
                    put(key, obj.optString(key, ""))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun headersToJson(headers: Map<String, String>): String {
        val obj = JSONObject()
        headers.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }
}
