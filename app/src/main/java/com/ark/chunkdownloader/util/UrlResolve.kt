package com.ark.chunkdownloader.util

import com.ark.chunkdownloader.data.model.AppSettings
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Baidu PCS/CDN direct-link helpers (aligned with ArkDownloader Mobile/PC).
 *
 * Filename note: Baidu `fin=` is usually **UTF-8** percent-encoded (same as uni-app
 * `decodeURIComponent`). Older links may use GBK. Never prefer GBK when UTF-8 is valid —
 * GBK mis-decode of UTF-8 bytes often still looks like CJK and used to win a naive score.
 */
object UrlResolve {
    private val BAIDU_HOST_MARKERS = listOf(
        "baidupcs.com",
        "antpcdn.com",
        "jomodns.com",
        "pcs.baidu.com"
    )

    private val GB18030: Charset = runCatching { Charset.forName("GB18030") }
        .getOrElse { Charset.forName("GBK") }
    private val GBK: Charset = Charset.forName("GBK")

    private val ILLEGAL_FILE_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    fun normalizeUrl(url: String): String =
        url.trim().replace("htype=&randtype=", "htype&randtype")

    fun isBaiduUrl(url: String): Boolean {
        val u = url.lowercase()
        if (BAIDU_HOST_MARKERS.none { it in u }) return false
        return "size=" in u && "/file/" in u
    }

    fun parseBaiduSize(url: String): Long {
        return rawQueryParam(url, "size")?.toLongOrNull()?.takeIf { it > 0 } ?: 0L
    }

    fun parseFin(url: String): String {
        val raw = rawQueryParam(url, "fin") ?: return ""
        return sanitizeFileName(decodeEncodedName(raw))
    }

    fun resolveHttpFileName(
        url: String,
        contentDisposition: String? = null,
        nameHint: String? = null
    ): String {
        nameHint?.takeIf { it.isNotBlank() }?.let {
            return sanitizeFileName(decodeEncodedName(it))
        }
        // Quark/COS signed URLs: response-content-disposition is reliable UTF-8 percent-encoding.
        // Prefer it over HTTP Content-Disposition (often Latin-1 / GBK mojibake).
        if (isQuarkOrCdnUrl(url)) {
            extractNameFromHttpUrl(url)
                ?.takeIf { it.isNotBlank() && !looksGarbled(it) && it != "download.bin" }
                ?.let { return it }
        }
        val candidates = LinkedHashSet<String>()
        parseContentDispositionName(contentDisposition)?.let { candidates += it }
        extractNameFromHttpUrl(url)?.let { candidates += it }
        inferFileName(url, fallback = "").takeIf { it.isNotBlank() && it != "download.bin" }
            ?.let { candidates += it }

        val good = candidates.filter { it.isNotBlank() && !looksGarbled(it) && it != "download.bin" }
        if (good.isNotEmpty()) {
            return good.maxByOrNull { scoreName(it) }!!
        }
        return candidates.maxByOrNull { scoreName(it) }?.takeIf { it.isNotBlank() }
            ?: "download.bin"
    }

    fun isQuarkOrCdnUrl(url: String): Boolean {
        val u = url.lowercase()
        return listOf(
            "quark.cn",
            "myqcloud.com",
            "aliyuncs.com",
            "qcloud.com",
            "drive.quark",
            "response-content-disposition=",
            "x-oss-meta-filename="
        ).any { it in u }
    }

    /**
     * Best display name for a task: re-parse from URL when stored name looks garbled.
     * Fixes tasks created before the UTF-8-first decoder.
     */
    fun displayFileName(stored: String?, url: String): String {
        val local = stored?.trim().orEmpty()
        val fromUrl = when {
            isBaiduUrl(url) -> parseFin(url)
            else -> extractNameFromHttpUrl(url).orEmpty()
        }.trim()

        // Quark/OSS: always prefer a clean name from the signed URL when available.
        if (isQuarkOrCdnUrl(url) && fromUrl.isNotBlank() && !looksGarbled(fromUrl)) {
            return fromUrl
        }

        if (local.isBlank()) return fromUrl.ifBlank { "download.bin" }
        if (fromUrl.isBlank()) {
            val repaired = tryRepairStoredName(local)
            return repaired ?: sanitizeFileName(local)
        }

        if (looksGarbled(local) && !looksGarbled(fromUrl)) return fromUrl
        if (scoreName(fromUrl) > scoreName(local) + 4) return fromUrl
        tryRepairStoredName(local)?.let { return it }

        return sanitizeFileName(local)
    }

    fun inferFileName(url: String, fallback: String = "download.bin"): String {
        if (isBaiduUrl(url)) {
            val fin = parseFin(url)
            if (fin.isNotBlank()) return fin
            return "baidu_download.bin"
        }
        return try {
            val pathSeg = url.substringBefore('?').substringAfterLast('/')
            val decoded = decodeEncodedName(pathSeg)
            if (decoded.isNotBlank() && decoded.contains('.')) {
                sanitizeFileName(decoded)
            } else {
                fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

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
            val name = nameHint?.takeIf { it.isNotBlank() }?.let { sanitizeFileName(decodeEncodedName(it)) }
                ?: parseFin(normalized).ifBlank { "baidu_download.bin" }
            return ResolvedUrl(normalized, size, name, Kind.BAIDU)
        }
        val name = nameHint?.takeIf { it.isNotBlank() }?.let { sanitizeFileName(decodeEncodedName(it)) }
            ?: extractNameFromHttpUrl(normalized)
            ?: inferFileName(normalized)
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

    fun chunkBytes(chunkSizeMbOrBytes: Int, kind: Kind): Int {
        val bytes = if (chunkSizeMbOrBytes <= AppSettings.MAX_CHUNK_MB) {
            AppSettings.chunkBytesFromMb(chunkSizeMbOrBytes)
        } else {
            chunkSizeMbOrBytes.coerceIn(AppSettings.MIN_CHUNK, AppSettings.MAX_CHUNK)
        }
        return if (kind == Kind.BAIDU) minOf(bytes, AppSettings.CHUNK_BAIDU) else bytes
    }

    fun decodeEncodedName(raw: String): String {
        if (raw.isBlank()) return ""
        var candidate = raw.trim().replace('+', ' ')

        // Nested percent-encoding
        repeat(3) {
            if ('%' !in candidate) return@repeat
            val once = percentDecodeToStringBest(candidate)
            if (once == candidate) return@repeat
            candidate = once
        }

        if ('%' in candidate) {
            candidate = percentDecodeToStringBest(candidate)
        } else if (looksGarbled(candidate)) {
            candidate = pickBestName(
                candidate,
                repairMojibake(candidate, StandardCharsets.UTF_8),
                repairMojibake(candidate, GB18030),
                repairMojibake(candidate, GBK)
            )
        }
        return candidate.trim()
    }

    fun sanitizeFileName(name: String): String {
        var n = name.trim().trimEnd('.', ' ')
        n = ILLEGAL_FILE_CHARS.replace(n, "_")
        if (n.isBlank() || n == "." || n == "..") return "download.bin"
        val base = n.substringBeforeLast('.', n)
        if (base.equals("CON", true) || base.equals("PRN", true) ||
            base.equals("AUX", true) || base.equals("NUL", true)
        ) {
            n = "_$n"
        }
        return n.take(200)
    }

    fun parseContentDispositionName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val hd = if ('%' in header && header.contains("filename", ignoreCase = true)) {
            runCatching { decodeEncodedName(header) }.getOrDefault(header)
        } else {
            header
        }

        // RFC 5987/8187 filename*=charset'lang'value — prefer over plain filename=
        val starAny = Regex(
            """filename\*\s*=\s*([^';\s]+)\s*'\s*[^']*'\s*([^;]+)""",
            RegexOption.IGNORE_CASE
        ).find(hd)
        if (starAny != null) {
            val csName = starAny.groupValues[1].trim().trim('"', '\'')
            val enc = starAny.groupValues[2].trim().trim('"', '\'')
            val charset = runCatching { Charset.forName(csName) }
                .getOrDefault(StandardCharsets.UTF_8)
            val bytes = percentDecodeToBytes(enc)
            val decoded = runCatching { String(bytes, charset).trim() }.getOrDefault("")
            if (decoded.isNotBlank() && !looksGarbled(decoded)) {
                return sanitizeFileName(decoded)
            }
            val loose = decodeEncodedName(enc)
            if (loose.isNotBlank()) return sanitizeFileName(loose)
        }

        val starUtf = Regex(
            """filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""",
            RegexOption.IGNORE_CASE
        ).find(hd)
        if (starUtf != null) {
            val decoded = decodeEncodedName(starUtf.groupValues[1].trim().trim('"', '\''))
            if (decoded.isNotBlank()) return sanitizeFileName(decoded)
        }

        // RFC 2047 encoded-word
        val rfc2047 = Regex(
            """=\?([^?]+)\?([BbQq])\?([^?]+)\?=""",
            RegexOption.IGNORE_CASE
        ).find(hd)
        if (rfc2047 != null) {
            decodeRfc2047(rfc2047.value)?.let { return sanitizeFileName(it) }
        }

        val plain = Regex(
            """filename\s*=\s*"([^"]*)"|filename\s*=\s*([^;\s]+)""",
            RegexOption.IGNORE_CASE
        ).find(hd)
        if (plain != null) {
            var raw = (plain.groupValues[1].ifBlank { plain.groupValues[2] }).trim()
            // Non-standard: filename=utf-8''%E4%B8%AD.pdf (missing *)
            val charsetPrefix = Regex(
                """^(?:UTF-8|utf-8|GBK|gbk|GB2312|gb2312|GB18030|gb18030)''(.+)$""",
                RegexOption.IGNORE_CASE
            ).find(raw)
            if (charsetPrefix != null) {
                raw = charsetPrefix.groupValues[1]
            }
            if ('%' in raw) {
                val decoded = decodeEncodedName(raw)
                if (decoded.isNotBlank() && !looksGarbled(decoded)) {
                    return sanitizeFileName(decoded)
                }
            }
            val repaired = repairHeaderFileName(raw)
            if (repaired.isNotBlank() && !looksGarbled(repaired)) {
                return sanitizeFileName(repaired)
            }
            // Last resort — return repaired even if scoring is uncertain
            if (repaired.isNotBlank() && scoreName(repaired) > scoreName(raw)) {
                return sanitizeFileName(repaired)
            }
        }
        return null
    }

    /**
     * Quark / OSS / CDN: name often in query `response-content-disposition`,
     * `filename`, `fname`, `attname`, …
     */
    fun extractNameFromHttpUrl(url: String): String? {
        val keys = listOf(
            "response-content-disposition",
            "x-oss-meta-filename",
            "filename",
            "fileName",
            "file_name",
            "fname",
            "attname",
            "download",
            "name"
        )
        val candidates = LinkedHashSet<String>()
        for (key in keys) {
            val raw = rawQueryParam(url, key) ?: continue
            // Decode once and twice — Quark/COS often double-encode CD blobs.
            val once = decodeEncodedName(raw)
            val twice = if ('%' in once) decodeEncodedName(once) else once
            for (blob in listOf(twice, once, raw.replace('+', ' '))) {
                if (blob.contains("filename", ignoreCase = true) ||
                    blob.contains("attachment", ignoreCase = true) ||
                    blob.contains("inline", ignoreCase = true)
                ) {
                    parseContentDispositionName(blob)?.let { candidates += it }
                }
                val asName = sanitizeFileName(blob)
                if (asName.contains('.') && !looksGarbled(asName) && asName != "download.bin" &&
                    !asName.contains('=') && asName.length in 3..180
                ) {
                    candidates += asName
                }
            }
        }
        val pathSeg = url.substringBefore('?').substringAfterLast('/')
        if (pathSeg.isNotBlank() && (pathSeg.contains('.') || '%' in pathSeg)) {
            val decoded = decodeEncodedName(pathSeg)
            if (decoded.isNotBlank() && !looksGarbled(decoded) && decoded.contains('.')) {
                candidates += sanitizeFileName(decoded)
            }
        }
        val good = candidates.filter { !looksGarbled(it) && it != "download.bin" }
        return good.maxByOrNull { scoreName(it) } ?: candidates.maxByOrNull { scoreName(it) }
    }

    private fun tryRepairStoredName(local: String): String? {
        if (local.isBlank()) return null
        val repaired = repairHeaderFileName(local)
        return if (repaired.isNotBlank() &&
            repaired != local &&
            !looksGarbled(repaired) &&
            scoreName(repaired) > scoreName(local) + 2
        ) {
            sanitizeFileName(repaired)
        } else null
    }

    private fun rawQueryParam(url: String, key: String): String? {
        val q = url.indexOf('?')
        if (q < 0) return null
        // Drop fragment
        val query = url.substring(q + 1).substringBefore('#')
        val marker = "$key="
        var from = 0
        while (from <= query.length) {
            val idx = query.indexOf(marker, from, ignoreCase = true)
            if (idx < 0) return null
            val boundaryOk = idx == 0 || query[idx - 1] == '&'
            if (boundaryOk) {
                val start = idx + marker.length
                val end = query.indexOf('&', start).let { if (it < 0) query.length else it }
                return query.substring(start, end).takeIf { it.isNotBlank() }
            }
            from = idx + 1
        }
        return null
    }

    /** OkHttp exposes raw header bytes as ISO-8859-1 code units — reinterpret. */
    private fun repairHeaderFileName(raw: String): String {
        if (raw.isBlank()) return ""
        if ('%' in raw) {
            val pct = decodeEncodedName(raw)
            if (pct.isNotBlank() && !looksGarbled(pct)) return pct
        }
        // Already good CJK / ASCII
        if (!looksGarbled(raw) && (raw.any { it in '\u4E00'..'\u9FFF' } || raw.all { it.code < 128 })) {
            return raw
        }
        val bytes = ByteArray(raw.length) { i -> raw[i].code.coerceIn(0, 255).toByte() }
        val utf8 = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrDefault("")
        val gbk = runCatching { String(bytes, GBK) }.getOrDefault("")
        val gb18030 = runCatching { String(bytes, GB18030) }.getOrDefault("")
        return when {
            isStrictUtf8(bytes) && !looksGarbled(utf8) -> utf8
            !looksGarbled(gb18030) && scoreName(gb18030) >= scoreName(utf8) -> gb18030
            !looksGarbled(gbk) -> gbk
            !looksGarbled(utf8) -> utf8
            else -> pickBestName(utf8, gb18030, gbk, raw)
        }
    }

    private fun decodeRfc2047(word: String): String? {
        val m = Regex("""=\?([^?]+)\?([BbQq])\?([^?]+)\?=""").find(word) ?: return null
        return try {
            val charset = Charset.forName(m.groupValues[1].trim())
            val enc = m.groupValues[2].first().uppercaseChar()
            val payload = m.groupValues[3]
            val bytes = if (enc == 'B') {
                java.util.Base64.getMimeDecoder().decode(payload)
            } else {
                // Q-encoding: _ = space, =XX hex
                val q = payload.replace('_', ' ')
                percentDecodeToBytes(q.replace(Regex("=([0-9A-Fa-f]{2})")) { "%${it.groupValues[1]}" })
            }
            String(bytes, charset).trim()
        } catch (_: Exception) {
            null
        }
    }

    private fun percentDecodeToStringBest(encoded: String): String {
        val bytes = percentDecodeToBytes(encoded)
        if (bytes.isEmpty()) return encoded

        // UTF-8 first — matches uni-app decodeURIComponent / PC behavior.
        if (isStrictUtf8(bytes)) {
            val utf8 = String(bytes, StandardCharsets.UTF_8)
            if (!looksGarbled(utf8)) return utf8
        }

        val utf8Loose = String(bytes, StandardCharsets.UTF_8)
        val gbk = runCatching { String(bytes, GBK) }.getOrDefault("")
        val gb18030 = runCatching { String(bytes, GB18030) }.getOrDefault("")

        // Only fall back to GB* when UTF-8 is invalid / broken.
        if (!isStrictUtf8(bytes) || looksGarbled(utf8Loose)) {
            return pickBestName(gb18030, gbk, utf8Loose)
        }
        return utf8Loose
    }

    private fun percentDecodeToBytes(encoded: String): ByteArray {
        // Manual decode — do NOT use URLDecoder (it treats '+' as space mid-sequence).
        return manualPercentDecode(encoded.replace('+', ' '))
    }

    private fun manualPercentDecode(encoded: String): ByteArray {
        val out = ArrayList<Byte>(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            if (c == '%' && i + 2 < encoded.length) {
                val hex = encoded.substring(i + 1, i + 3)
                val b = hex.toIntOrNull(16)
                if (b != null) {
                    out.add(b.toByte())
                    i += 3
                    continue
                }
            }
            // Keep BMP code unit low byte for ASCII; non-ASCII already-decoded chars:
            // encode as UTF-8 bytes so round-trip stays consistent.
            if (c.code <= 0x7F) {
                out.add(c.code.toByte())
            } else {
                val utf = c.toString().toByteArray(StandardCharsets.UTF_8)
                utf.forEach { out.add(it) }
            }
            i++
        }
        return out.toByteArray()
    }

    private fun isStrictUtf8(bytes: ByteArray): Boolean {
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun repairMojibake(text: String, charset: Charset): String {
        return try {
            if (text.any { it.code > 0xFF }) {
                String(text.toByteArray(StandardCharsets.ISO_8859_1), charset)
            } else {
                val bytes = ByteArray(text.length) { i -> text[i].code.coerceIn(0, 255).toByte() }
                String(bytes, charset)
            }
        } catch (_: Exception) {
            text
        }
    }

    private fun pickBestName(vararg candidates: String): String {
        return candidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .maxByOrNull { scoreName(it) }
            ?: candidates.firstOrNull().orEmpty()
    }

    /** Heuristic: mojibake / replacement / odd Latin leftovers from bad charset. */
    private fun looksGarbled(s: String): Boolean {
        if (s.isBlank()) return true
        if ('\uFFFD' in s) return true
        var cjk = 0
        var latinExt = 0
        var weird = 0
        for (ch in s) {
            when {
                ch in '\u4E00'..'\u9FFF' -> cjk++
                ch in '\u00C0'..'\u024F' || ch in '\u0100'..'\u017F' -> latinExt++
                ch.code < 0x20 -> weird++
                // Common UTF-8→Latin1 mojibake markers
                ch in "ÃÂäåæçèéêëìíîïðñòóôõöøùúûüýþÿÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖØÙÚÛÜÝÞß" -> latinExt++
            }
        }
        if (latinExt >= 2 && cjk == 0) return true
        if (latinExt > cjk && latinExt >= 3) return true
        return false
    }

    private fun scoreName(s: String): Int {
        if (s.isBlank()) return Int.MIN_VALUE / 2
        var score = 0
        var cjk = 0
        var replacement = 0
        var weird = 0
        var latinExt = 0
        for (ch in s) {
            when {
                ch == '\uFFFD' -> replacement++
                ch.code < 0x20 -> weird++
                ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' ||
                    ch in '\uF900'..'\uFAFF' || ch in '\uAC00'..'\uD7AF' -> cjk++
                ch in '\u00C0'..'\u024F' -> latinExt++
                ch.isLetterOrDigit() || ch in "._- ()[]【】（）·、，。" -> score += 1
                else -> weird++
            }
        }
        score += cjk * 8
        score -= replacement * 50
        score -= latinExt * 6
        score -= weird * 3
        if ('.' in s) score += 5
        if (s.length in 2..120) score += 2
        return score
    }

    enum class Kind { BAIDU, HTTP }

    data class ResolvedUrl(
        val url: String,
        val size: Long,
        val name: String,
        val kind: Kind
    )
}
