package com.ark.chunkdownloader.download

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import com.ark.chunkdownloader.util.UrlResolve
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Shared OkHttp multi-range downloader.
 * Writes ranges directly into a pre-allocated [FileChannel] at absolute offsets
 * (no per-chunk .partN files / merge).
 */
class ChunkDownloader(
    private val client: OkHttpClient = sharedClient()
) {
    data class ProbeResult(
        val totalSize: Long,
        val supportsRange: Boolean,
        val statusCode: Int,
        val suggestedName: String? = null,
        val contentDisposition: String? = null
    )

    /**
     * Prefer Range GET probe (Baidu CDN often rejects HEAD).
     * When [forceHeaders] is true, still hit the network even if [knownSize] > 0
     * (needed to read Content-Disposition for Quark / OSS filenames).
     */
    fun probe(
        url: String,
        headers: Map<String, String>,
        userAgent: String?,
        knownSize: Long = -1L,
        skipHead: Boolean = false,
        forceHeaders: Boolean = false
    ): ProbeResult {
        if (knownSize > 0 && !forceHeaders) {
            return ProbeResult(knownSize, supportsRange = true, statusCode = 200)
        }
        fun fromResponse(resp: okhttp3.Response, fallbackLen: Long): ProbeResult {
            val cd = resp.header("Content-Disposition")
                ?: resp.header("content-disposition")
            val name = UrlResolve.resolveHttpFileName(url, cd, null)
                .takeIf { it.isNotBlank() && it != "download.bin" }
            val len = when {
                fallbackLen > 0 -> fallbackLen
                resp.code == 206 -> parseContentRangeTotal(resp.header("Content-Range"))
                else -> resp.header("Content-Length")?.toLongOrNull() ?: -1L
            }
            val accept = resp.code == 206 ||
                resp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
            return ProbeResult(
                totalSize = if (knownSize > 0) knownSize else len,
                supportsRange = accept,
                statusCode = resp.code,
                suggestedName = name,
                contentDisposition = cd
            )
        }
        if (!skipHead) {
            val headReq = buildRequest(url, headers, userAgent, range = null)
            try {
                client.newCall(headReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val len = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                        val result = fromResponse(resp, len)
                        if (result.totalSize > 0 || !result.suggestedName.isNullOrBlank() || forceHeaders) {
                            if (result.totalSize > 0 || !result.suggestedName.isNullOrBlank()) {
                                return result
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // fall through to Range GET
            }
        }
        val getReq = buildRequest(url, headers, userAgent, 0L to 0L)
        client.newCall(getReq).execute().use { resp ->
            return fromResponse(resp, -1L)
        }
    }

    /**
     * Download [range] into [channel] at absolute file offset [writeOffset].
     * Prefer a long-lived per-worker channel over opening a new RAF per chunk.
     */
    fun downloadRange(
        url: String,
        range: LongRange,
        channel: FileChannel,
        writeOffset: Long,
        headers: Map<String, String>,
        userAgent: String?,
        isCancelled: () -> Boolean = { false },
        onCall: ((Call) -> Unit)? = null,
        onCallDone: ((Call) -> Unit)? = null,
        onBytes: (Long) -> Unit = {}
    ): Long {
        val req = buildRequest(url, headers, userAgent, range.first to range.last)
        val call = client.newCall(req)
        onCall?.invoke(call)
        try {
            if (isCancelled() || call.isCanceled()) {
                throw InterruptedIOException("cancelled")
            }
            call.execute().use { resp ->
                if (isCancelled() || call.isCanceled()) {
                    throw InterruptedIOException("cancelled")
                }
                if (!resp.isSuccessful && resp.code != 206) {
                    val body = resp.body?.string()?.take(300)
                    throw IllegalStateException("HTTP ${resp.code}: ${body ?: resp.message}")
                }
                val body = resp.body ?: throw IllegalStateException("Empty body")
                var position = writeOffset
                body.byteStream().use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    val wrap = ByteBuffer.wrap(buf)
                    var written = 0L
                    while (true) {
                        if (isCancelled() || call.isCanceled() || Thread.currentThread().isInterrupted) {
                            throw InterruptedIOException("cancelled")
                        }
                        val n = input.read(buf)
                        if (n == -1) break
                        wrap.clear()
                        wrap.limit(n)
                        // Positioned write is safe across threads for non-overlapping ranges.
                        var remaining = n
                        var at = position
                        while (remaining > 0) {
                            val w = channel.write(wrap, at)
                            if (w <= 0) {
                                throw IllegalStateException("FileChannel write returned $w")
                            }
                            at += w
                            remaining -= w
                        }
                        position = at
                        written += n
                        onBytes(n.toLong())
                    }
                    return written
                }
            }
        } finally {
            onCallDone?.invoke(call)
        }
    }

    /** Convenience: open file for a single range (slower — prefer shared per-worker channel). */
    fun downloadRange(
        url: String,
        range: LongRange,
        destFile: File,
        writeOffset: Long,
        headers: Map<String, String>,
        userAgent: String?,
        isCancelled: () -> Boolean = { false },
        onCall: ((Call) -> Unit)? = null,
        onCallDone: ((Call) -> Unit)? = null,
        onBytes: (Long) -> Unit = {}
    ): Long {
        destFile.parentFile?.mkdirs()
        RandomAccessFile(destFile, "rw").use { raf ->
            return downloadRange(
                url = url,
                range = range,
                channel = raf.channel,
                writeOffset = writeOffset,
                headers = headers,
                userAgent = userAgent,
                isCancelled = isCancelled,
                onCall = onCall,
                onCallDone = onCallDone,
                onBytes = onBytes
            )
        }
    }

    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
        userAgent: String?,
        range: Pair<Long, Long>?
    ): Request {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) ->
            val key = k.lowercase()
            if (key != "range" && key != "user-agent") {
                builder.header(k, v)
            }
        }
        if (!userAgent.isNullOrBlank()) builder.header("User-Agent", userAgent)
        builder.header("Accept", "*/*")
        builder.header("Accept-Encoding", "identity")
        builder.header("Accept-Language", "zh-CN")
        builder.header("Connection", "Keep-Alive")
        if (range != null) builder.header("Range", "bytes=${range.first}-${range.second}")
        return builder.build()
    }

    private fun parseContentRangeTotal(header: String?): Long {
        if (header.isNullOrBlank()) return -1L
        val totalPart = header.substringAfter('/').substringBefore(';').trim()
        return totalPart.toLongOrNull() ?: -1L
    }

    companion object {
        private const val BUFFER_SIZE = 512 * 1024

        @Volatile
        private var cached: OkHttpClient? = null

        fun sharedClient(): OkHttpClient {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val dispatcher = Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 16
                }
                val client = OkHttpClient.Builder()
                    .dispatcher(dispatcher)
                    .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
                    .protocols(listOf(Protocol.HTTP_1_1))
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
                cached = client
                return client
            }
        }

        fun buildChunks(totalSize: Long, chunkSize: Int): List<LongRange> {
            if (totalSize <= 0) return emptyList()
            val size = chunkSize.coerceAtLeast(1)
            val chunks = mutableListOf<LongRange>()
            var start = 0L
            while (start < totalSize) {
                val end = min(start + size - 1, totalSize - 1)
                chunks += start..end
                start = end + 1
            }
            return chunks
        }
    }
}
