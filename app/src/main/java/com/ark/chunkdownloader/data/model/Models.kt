package com.ark.chunkdownloader.data.model

enum class TaskStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    MERGING,
    COMPLETED,
    FAILED,
    CANCELED;

    /** Legacy / RPC display strings. */
    fun toApi(): String = when (this) {
        PENDING -> "pending"
        DOWNLOADING -> "running"
        PAUSED -> "paused"
        MERGING -> "merging"
        COMPLETED -> "done"
        FAILED -> "error"
        CANCELED -> "error"
    }

    /** Gopeed / mobile-aligned status. */
    fun toV1(): String = when (this) {
        PENDING -> "pending"
        DOWNLOADING, MERGING -> "running"
        PAUSED -> "paused"
        COMPLETED -> "done"
        FAILED -> "error"
        CANCELED -> "error"
    }

    companion object {
        fun fromApi(value: String?): TaskStatus? = when (value?.lowercase()) {
            "pending" -> PENDING
            "downloading", "running" -> DOWNLOADING
            "paused" -> PAUSED
            "merging" -> MERGING
            "completed", "done" -> COMPLETED
            "failed", "error" -> FAILED
            "cancelled", "canceled" -> CANCELED
            else -> null
        }
    }
}

data class DownloadTask(
    val id: String,
    val url: String,
    val fileName: String,
    val saveDir: String,
    val filePath: String,
    val totalSize: Long,
    val loaded: Long,
    val speed: Long,
    val status: TaskStatus,
    val errorMsg: String?,
    val threads: Int,
    val chunkSize: Int,
    val userAgent: String?,
    val headersJson: String,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?
) {
    val progress: Float
        get() = if (totalSize > 0) (loaded.toFloat() / totalSize * 100f).coerceIn(0f, 100f) else 0f

    /** Alias for mobile/PC speedBps field. */
    val speedBps: Long get() = speed
}

data class AppSettings(
    val defaultSaveDir: String = "",
    val maxThreads: Int = DEFAULT_CONNECTIONS,
    val maxConcurrentTasks: Int = DEFAULT_MAX_RUNNING,
    val chunkSize: Int = DEFAULT_CHUNK,
    val autoStart: Boolean = true,
    val notifyOnComplete: Boolean = true,
    val userAgent: String = BAIDU_UA,
    val httpUserAgent: String = DEFAULT_HTTP_USER_AGENT,
    val defaultHeadersJson: String = "{}",
    val rpcEnabled: Boolean = true,
    val rpcPort: Int = DEFAULT_RPC_PORT,
    val rpcRemote: Boolean = true,
    val rpcToken: String = ""
) {
    val downloadDir: String get() = defaultSaveDir
    val connections: Int get() = maxThreads
    val maxRunning: Int get() = maxConcurrentTasks
    val chunkSizeMb: Int get() = (chunkSize / (1024 * 1024)).coerceIn(MIN_CHUNK_MB, MAX_CHUNK_MB)

    companion object {
        const val APP_NAME = "ArkDownloader"
        const val VERSION = "1.0.12"
        const val MIN_CHUNK_MB = 1
        const val MAX_CHUNK_MB = 5
        const val MIN_CHUNK = MIN_CHUNK_MB * 1024 * 1024
        const val MAX_CHUNK = MAX_CHUNK_MB * 1024 * 1024
        const val DEFAULT_CHUNK = MIN_CHUNK
        /** Baidu PCS single-chunk cap (5 MiB). */
        const val CHUNK_BAIDU = MAX_CHUNK
        const val MAX_THREADS = 16
        const val DEFAULT_CONNECTIONS = 8
        const val MAX_RUNNING = 10
        const val DEFAULT_MAX_RUNNING = 3
        const val DEFAULT_RPC_PORT = 18766
        /** Baidu netdisk UA (matches ArkDownloader Mobile/PC). */
        const val BAIDU_UA =
            "netdisk;P2SP;3.0.20.233;netdisk;8.7.9.102;PC;PC-Windows;10.0.19045;WindowsBaiduYunGuanJia"
        const val DEFAULT_USER_AGENT = BAIDU_UA
        const val DEFAULT_HTTP_USER_AGENT = "ArkDownloader/1.0"

        fun chunkBytesFromMb(mb: Int): Int =
            mb.coerceIn(MIN_CHUNK_MB, MAX_CHUNK_MB) * 1024 * 1024
    }
}
