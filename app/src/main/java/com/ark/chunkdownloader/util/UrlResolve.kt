package com.ark.chunkdownloader.util

import android.net.Uri
import com.ark.chunkdownloader.data.model.AppSettings
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Baidu PCS/CDN direct-link helpers (aligned with ArkDownloader Mobile/PC).
 */
object UrlResolve {
    private val BAIDU_HOST_MARKERS = listOf(
        "baidupcs.com",
        "antpcdn.com",
        "jomodns.com",
        "pcs.baidu.com"
    )

    fun normalizeUrl(url: String): String =
        url.trim().replace("htype=&randtype=", "htype&randtype")

    fun isBaiduUrl(url: String): Boolean {
        val u = url.lowercase()
        if (BAIDU_HOST_MARKERS.none { it in u }) return false
        return "size=" in u && "/file/" in u
    }

    fun parseBaiduSize(url: String): Long {
        return queryParam(url, "size")?.toLongOrNull()?.takeIf { it > 0 } ?: 0L
    }

    fun parseFin(url: String): String {
        val raw = queryParam(url, "fin") ?: return ""
        return try {
            URLDecoder.decode(raw, StandardCharsets.UTF_8.name()).replace('+', ' ').trim()
        } catch (_: Exception) {
            raw.replace('+', ' ').trim()
        }
    }

    fun inferFileName(url: String, fallback: String = "download.bin"): String {
        if (isBaiduUrl(url)) {
            val fin = parseFin(url)
            if (fin.isNotBlank()) return fin
            return "baidu_download.bin"
        }
        return try {
            val path = url.substringBefore('?').substringAfterLast('/')
            val decoded = URLDecoder.decode(path, StandardCharsets.UTF_8.name())
            if (decoded.isNotBlank() && decoded.contains('.')) decoded else fallback
        } catch (_: Exception) {
            fallback
        }
    }

    /**
     * Resolve (url, size, name, kind) without blocking UI.
     * Baidu size comes from the URL; HTTP may need a later probe.
     */
    fun resolve(
        url: String,
        nameHint: String? = null,
        sizeHint: Long = 0L
    ): ResolvedUrl {
        val normalized = normalizeUrl(url)
        if (isBaiduUrl(normalized)) {
            val size = parseBaiduSize(normalized).takeIf { it > 0 } ?: sizeHint
            if (size <= 0) {
                throw IllegalArgumentException("百度直链缺少 size= 参数")
            }
            val name = nameHint?.takeIf { it.isNotBlank() }
                ?: parseFin(normalized).ifBlank { "baidu_download.bin" }
            return ResolvedUrl(normalized, size, name, Kind.BAIDU)
        }
        val name = nameHint?.takeIf { it.isNotBlank() } ?: inferFileName(normalized)
        return ResolvedUrl(normalized, sizeHint.coerceAtLeast(0), name, Kind.HTTP)
    }

    fun pickUserAgent(
        kind: Kind,
        settings: AppSettings,
        override: String? = null
    ): String {
        override?.takeIf { it.isNotBlank() }?.let { return it }
        return when (kind) {
            Kind.BAIDU -> settings.userAgent.ifBlank { AppSettings.BAIDU_UA }
            Kind.HTTP -> settings.httpUserAgent.ifBlank { AppSettings.DEFAULT_HTTP_USER_AGENT }
        }
    }

    fun pickUserAgent(url: String, settings: AppSettings, override: String? = null): String {
        val kind = if (isBaiduUrl(url)) Kind.BAIDU else Kind.HTTP
        return pickUserAgent(kind, settings, override)
    }

    /** Accepts either MB (1–5) or raw bytes; Baidu capped at [AppSettings.CHUNK_BAIDU]. */
    fun chunkBytes(chunkSizeMbOrBytes: Int, kind: Kind): Int {
        val bytes = if (chunkSizeMbOrBytes <= AppSettings.MAX_CHUNK_MB) {
            AppSettings.chunkBytesFromMb(chunkSizeMbOrBytes)
        } else {
            chunkSizeMbOrBytes.coerceIn(AppSettings.MIN_CHUNK, AppSettings.MAX_CHUNK)
        }
        return if (kind == Kind.BAIDU) minOf(bytes, AppSettings.CHUNK_BAIDU) else bytes
    }

    private fun queryParam(url: String, key: String): String? {
        return try {
            Uri.parse(url).getQueryParameter(key)
        } catch (_: Exception) {
            // Fallback for odd encodings Uri may reject
            val marker = "$key="
            val idx = url.indexOf(marker, ignoreCase = true)
            if (idx < 0) return null
            val start = idx + marker.length
            val end = url.indexOf('&', start).let { if (it < 0) url.length else it }
            url.substring(start, end).takeIf { it.isNotBlank() }
        }
    }

    enum class Kind { BAIDU, HTTP }

    data class ResolvedUrl(
        val url: String,
        val size: Long,
        val name: String,
        val kind: Kind
    )
}
