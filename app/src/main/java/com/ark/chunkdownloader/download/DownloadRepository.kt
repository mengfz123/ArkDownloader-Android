package com.ark.chunkdownloader.download

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.ark.chunkdownloader.data.db.AppDatabase
import com.ark.chunkdownloader.data.db.TaskEntity
import com.ark.chunkdownloader.data.model.AppSettings
import com.ark.chunkdownloader.data.model.TaskStatus
import com.ark.chunkdownloader.data.prefs.SettingsRepository
import com.ark.chunkdownloader.data.toModel
import com.ark.chunkdownloader.rpc.RpcServer
import com.ark.chunkdownloader.util.FilePublish
import com.ark.chunkdownloader.util.FormatUtil
import com.ark.chunkdownloader.util.UrlResolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class DownloadRepository(
    val context: Context,
    val settingsRepo: SettingsRepository,
    val engine: DownloadEngine,
    private val rpcServer: RpcServer
) : RpcServer.DownloadRepositoryBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = AppDatabase.get(context).taskDao()

    val tasksFlow = engine.observeTasks().map { list -> list.map { it.toModel() } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settingsFlow = settingsRepo.settingsFlow
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val rpcRunning: StateFlow<Boolean> = rpcServer.running

    @Volatile
    private var lastRpcFingerprint: String? = null

    fun initialize() {
        startDownloadService()
        scope.launch {
            settingsRepo.migrateRemoteAccessDefault()
            settingsRepo.settingsFlow.collect { settings ->
                syncRpc(settings)
            }
        }
        scope.launch { engine.resumePendingOnBoot() }
    }

    private fun startDownloadService() {
        val intent = Intent(context, DownloadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    /** Restart NanoHTTPD only when RPC listen/auth settings actually change. */
    private fun syncRpc(settings: AppSettings) {
        val fingerprint = listOf(
            settings.rpcEnabled,
            settings.rpcPort,
            settings.rpcRemote,
            settings.rpcToken
        ).joinToString("|")
        if (fingerprint == lastRpcFingerprint) return
        lastRpcFingerprint = fingerprint
        if (settings.rpcEnabled) {
            try {
                rpcServer.start(settings.rpcPort, settings.rpcToken, settings.rpcRemote, this)
            } catch (e: Exception) {
                android.util.Log.e("ArkDownloader", "RPC start failed: ${e.message}")
                lastRpcFingerprint = null
            }
        } else {
            rpcServer.stop()
        }
    }

    suspend fun createTaskFromUi(
        url: String,
        fileName: String?,
        threads: Int,
        chunkSize: Int,
        headersJson: String
    ): TaskEntity {
        val headers = FormatUtil.parseHeaders(headersJson)
        return engine.createTask(url, fileName, null, threads, chunkSize, null, null, headers)
    }

    suspend fun createTasksFromUrls(
        urls: List<String>,
        fileName: String?,
        threads: Int,
        chunkSize: Int,
        headersJson: String
    ): List<TaskEntity> {
        val cleaned = urls.map { it.trim() }.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return emptyList()
        val headers = FormatUtil.parseHeaders(headersJson)
        val created = mutableListOf<TaskEntity>()
        cleaned.forEach { url ->
            val name = if (cleaned.size == 1) fileName else null
            try {
                created += engine.createTask(url, name, null, threads, chunkSize, null, null, headers)
            } catch (e: Exception) {
                android.util.Log.w("ArkDownloader", "create failed: ${url.take(80)} — ${e.message}")
            }
        }
        return created
    }

    // ---------- RPC bridge ----------
    override suspend fun getInfo(): Map<String, Any?> {
        val s = settingsRepo.current()
        return mapOf(
            "name" to AppSettings.APP_NAME,
            "version" to AppSettings.VERSION,
            "downloadDir" to s.downloadDir.ifBlank {
                FilePublish.resolveWritableDir(context, null).absolutePath
            },
            "rpc" to rpcServer.status()
        )
    }

    override suspend fun resolveUrl(body: JSONObject): Map<String, Any?> {
        val url = body.optString("url").trim()
        if (url.isBlank()) throw IllegalArgumentException("url required")
        val nameHint = body.optString("name").takeIf { it.isNotBlank() }
        val sizeHint = body.optLong("size").takeIf { it > 0 }
            ?: body.optLong("total_size").takeIf { it > 0 }
            ?: 0L
        val resolved = UrlResolve.resolve(url, nameHint, sizeHint)
        var size = resolved.size
        if (size <= 0 && resolved.kind == UrlResolve.Kind.HTTP) {
            val settings = settingsRepo.current()
            val ua = UrlResolve.pickUserAgent(resolved.kind, settings, null)
            val probe = ChunkDownloader().probe(
                url = resolved.url,
                headers = emptyMap(),
                userAgent = ua,
                skipHead = false
            )
            size = probe.totalSize
            if (nameHint == null) {
                probe.suggestedName?.takeIf { it.isNotBlank() }?.let {
                    return mapOf(
                        "url" to resolved.url,
                        "size" to size,
                        "name" to it,
                        "kind" to if (resolved.kind == UrlResolve.Kind.BAIDU) "baidu" else "http"
                    )
                }
            }
        }
        return mapOf(
            "url" to resolved.url,
            "size" to size,
            "name" to resolved.name,
            "kind" to if (resolved.kind == UrlResolve.Kind.BAIDU) "baidu" else "http"
        )
    }

    override suspend fun listTasks(status: String?): List<Map<String, Any?>> {
        val all = dao.getAll()
        val filtered = if (status.isNullOrBlank()) {
            all
        } else {
            val wanted = TaskStatus.fromApi(status)
                ?: throw IllegalArgumentException("Unknown status filter: $status")
            all.filter { entity ->
                val st = runCatching { TaskStatus.valueOf(entity.status) }.getOrNull()
                    ?: TaskStatus.fromApi(entity.status)
                when (wanted) {
                    TaskStatus.DOWNLOADING ->
                        st == TaskStatus.DOWNLOADING || st == TaskStatus.MERGING || st == TaskStatus.PENDING
                    TaskStatus.FAILED ->
                        st == TaskStatus.FAILED || st == TaskStatus.CANCELED
                    else -> st == wanted
                }
            }
        }
        return filtered.map { taskToV1(it) }
    }

    override suspend fun getTaskEntity(id: String): TaskEntity? = dao.getById(id)

    override fun taskToDict(task: TaskEntity): Map<String, Any?> {
        val model = task.toModel()
        return mapOf(
            "id" to model.id,
            "url" to model.url,
            "file_name" to model.fileName,
            "save_dir" to model.saveDir,
            "file_path" to model.filePath,
            "total" to model.totalSize,
            "loaded" to model.loaded,
            "progress" to model.progress,
            "speed" to model.speed,
            "status" to model.status.toApi(),
            "error_msg" to model.errorMsg,
            "threads" to model.threads,
            "chunk_size" to model.chunkSize,
            "created_at" to model.createdAt,
            "started_at" to model.startedAt,
            "completed_at" to model.completedAt
        )
    }

    override suspend fun taskToV1(task: TaskEntity): Map<String, Any?> {
        val model = task.toModel()
        val kind = if (UrlResolve.isBaiduUrl(model.url)) "baidu" else "http"
        val chunks = dao.getChunks(task.id)
        return mapOf(
            "id" to model.id,
            "name" to model.fileName,
            "path" to model.saveDir,
            "url" to model.url,
            "size" to model.totalSize,
            "kind" to kind,
            "status" to model.status.toV1(),
            "connections" to model.threads,
            "chunkSize" to model.chunkSize,
            "downloaded" to model.loaded,
            "speed" to if (model.status == TaskStatus.DOWNLOADING) model.speed else 0L,
            "speedBps" to if (model.status == TaskStatus.DOWNLOADING) model.speedBps else 0L,
            "progress" to (if (model.totalSize > 0) {
                Math.round(model.loaded.toDouble() / model.totalSize * 10000.0) / 100.0
            } else 0.0),
            "chunks" to mapOf("total" to chunks.size, "done" to chunks.count { it.completed }),
            "error" to model.errorMsg,
            "createdAt" to model.createdAt,
            "updatedAt" to (model.completedAt ?: model.startedAt ?: model.createdAt),
            "out" to model.filePath
        )
    }

    override suspend fun getSettings(): Map<String, Any?> {
        val s = settingsRepo.current()
        val dir = s.downloadDir.ifBlank {
            FilePublish.resolveWritableDir(context, null).absolutePath
        }
        return mapOf(
            // pan-fetch / mobile keys
            "downloadDir" to dir,
            "connections" to s.connections,
            "maxRunning" to s.maxRunning,
            "chunkSizeMb" to s.chunkSizeMb,
            "autoStart" to s.autoStart,
            "notifyOnComplete" to s.notifyOnComplete,
            "userAgent" to s.userAgent,
            "httpUserAgent" to s.httpUserAgent,
            "rpcEnabled" to s.rpcEnabled,
            "rpcPort" to s.rpcPort,
            "rpcRemote" to s.rpcRemote,
            "rpcToken" to s.rpcToken,
            "rpcStatus" to rpcServer.status(),
            // legacy aliases
            "max_concurrent" to s.maxConcurrentTasks,
            "max_threads" to s.maxThreads,
            "chunk_size" to s.chunkSize,
            "default_save_path" to dir,
            "default_user_agent" to s.userAgent,
            "default_headers" to FormatUtil.parseHeaders(s.defaultHeadersJson),
            "rpc_enabled" to s.rpcEnabled,
            "rpc_port" to s.rpcPort
        )
    }

    override suspend fun updateSettings(body: JSONObject): Map<String, Any?> {
        settingsRepo.update { current ->
            var next = current
            fun has(key: String) = body.has(key) && !body.isNull(key)

            if (has("downloadDir") || has("default_save_path")) {
                val dir = body.optString(
                    if (has("downloadDir")) "downloadDir" else "default_save_path"
                )
                next = next.copy(defaultSaveDir = dir)
            }
            if (has("connections") || has("max_threads")) {
                val n = body.getInt(if (has("connections")) "connections" else "max_threads")
                next = next.copy(maxThreads = n)
            }
            if (has("maxRunning") || has("max_concurrent")) {
                val n = body.getInt(if (has("maxRunning")) "maxRunning" else "max_concurrent")
                next = next.copy(maxConcurrentTasks = n)
            }
            when {
                has("chunkSizeMb") ->
                    next = next.copy(chunkSize = AppSettings.chunkBytesFromMb(body.getInt("chunkSizeMb")))
                has("chunk_size") ->
                    next = next.copy(chunkSize = body.getInt("chunk_size"))
            }
            if (has("autoStart")) next = next.copy(autoStart = body.getBoolean("autoStart"))
            if (has("notifyOnComplete")) {
                next = next.copy(notifyOnComplete = body.getBoolean("notifyOnComplete"))
            }
            if (has("userAgent") || has("default_user_agent")) {
                val ua = body.optString(if (has("userAgent")) "userAgent" else "default_user_agent")
                next = next.copy(userAgent = ua)
            }
            if (has("httpUserAgent")) {
                next = next.copy(httpUserAgent = body.optString("httpUserAgent"))
            }
            if (has("default_headers")) {
                val h = body.getJSONObject("default_headers")
                val map = buildMap { h.keys().forEach { k -> put(k, h.getString(k)) } }
                next = next.copy(defaultHeadersJson = FormatUtil.headersToJson(map))
            }
            if (has("rpcEnabled") || has("rpc_enabled")) {
                val v = body.getBoolean(if (has("rpcEnabled")) "rpcEnabled" else "rpc_enabled")
                next = next.copy(rpcEnabled = v)
            }
            if (has("rpcPort") || has("rpc_port")) {
                val p = body.getInt(if (has("rpcPort")) "rpcPort" else "rpc_port")
                next = next.copy(rpcPort = p)
            }
            if (has("rpcRemote")) next = next.copy(rpcRemote = body.getBoolean("rpcRemote"))
            if (has("rpcToken") || has("rpc_token")) {
                val t = body.optString(if (has("rpcToken")) "rpcToken" else "rpc_token")
                next = next.copy(rpcToken = t)
            }
            next
        }
        return getSettings()
    }

    override suspend fun createTask(body: JSONObject): String {
        val task = createTaskFromBody(body)
        return task.id
    }

    override suspend fun createTasksBatch(body: JSONObject): Map<String, Any?> {
        val ids = mutableListOf<String>()
        val errors = mutableListOf<Map<String, String>>()
        val items: List<JSONObject> = when {
            body.has("reqs") -> jsonObjectList(body.getJSONArray("reqs"))
            body.has("tasks") -> jsonObjectList(body.getJSONArray("tasks"))
            body.has("urls") -> {
                val arr = body.getJSONArray("urls")
                (0 until arr.length()).map { i ->
                    JSONObject().put("url", arr.getString(i))
                }
            }
            else -> emptyList()
        }
        for (item in items) {
            val url = extractUrl(item)
            if (url.isBlank()) continue
            try {
                val merged = JSONObject(item.toString())
                if (!merged.has("url")) merged.put("url", url)
                ids.add(createTaskFromBody(merged).id)
            } catch (e: Exception) {
                errors.add(mapOf("url" to url.take(80), "error" to (e.message ?: e.toString())))
            }
        }
        if (ids.isEmpty() && errors.isNotEmpty()) {
            throw IllegalArgumentException(errors.first()["error"] ?: "batch failed")
        }
        return mapOf("ids" to ids, "errors" to errors)
    }

    private suspend fun createTaskFromBody(body: JSONObject): TaskEntity {
        val opts = when {
            body.has("opts") && body.get("opts") is JSONObject -> body.getJSONObject("opts")
            body.has("opt") && body.get("opt") is JSONObject -> body.getJSONObject("opt")
            else -> JSONObject()
        }
        val extra = if (opts.has("extra") && opts.get("extra") is JSONObject) {
            opts.getJSONObject("extra")
        } else JSONObject()

        val url = extractUrl(body)
        if (url.isBlank()) throw IllegalArgumentException("url required")

        val headers = normalizeHeaders(
            when {
                body.has("headers") && !body.isNull("headers") -> body.get("headers")
                extra.has("headers") -> extra.get("headers")
                else -> null
            }
        )

        val fileName = sequenceOf(
            opts.optString("name"),
            body.optString("name"),
            body.optString("file_name")
        ).map { it.trim() }.firstOrNull { it.isNotBlank() }

        val saveDir = sequenceOf(
            opts.optString("path"),
            body.optString("dir"),
            body.optString("save_path"),
            body.optString("save_dir")
        ).map { it.trim() }.firstOrNull { it.isNotBlank() }

        val threads = when {
            extra.has("connections") -> extra.getInt("connections")
            body.has("connections") -> body.getInt("connections")
            body.has("threads") -> body.getInt("threads")
            else -> null
        }

        val chunkSize = when {
            body.has("chunkSizeMb") -> AppSettings.chunkBytesFromMb(body.getInt("chunkSizeMb"))
            body.has("chunk_size") -> body.getInt("chunk_size")
            else -> null
        }

        val totalSize = when {
            body.has("size") -> body.optLong("size").takeIf { it > 0 }
            body.has("total_size") -> body.optLong("total_size").takeIf { it > 0 }
            else -> null
        }

        val ua = body.optString("user_agent").takeIf { it.isNotBlank() }
            ?: body.optString("userAgent").takeIf { it.isNotBlank() }

        return engine.createTask(
            url = url,
            fileName = fileName,
            saveDir = saveDir,
            threads = threads,
            chunkSize = chunkSize,
            totalSize = totalSize,
            userAgent = ua,
            headers = headers
        )
    }

    private fun extractUrl(body: JSONObject): String {
        if (body.has("url")) return body.optString("url").trim()
        if (body.has("req") && body.get("req") is JSONObject) {
            return body.getJSONObject("req").optString("url").trim()
        }
        return ""
    }

    private fun normalizeHeaders(raw: Any?): Map<String, String> {
        if (raw == null || raw == JSONObject.NULL) return emptyMap()
        return when (raw) {
            is JSONObject -> buildMap { raw.keys().forEach { k -> put(k, raw.getString(k)) } }
            else -> emptyMap()
        }
    }

    private fun jsonObjectList(arr: JSONArray): List<JSONObject> =
        (0 until arr.length()).mapNotNull { i ->
            val v = arr.get(i)
            v as? JSONObject
        }

    override suspend fun pauseTask(id: String) = engine.pauseTask(id)
    override suspend fun resumeTask(id: String) = engine.resumeTask(id)
    override suspend fun cancelTask(id: String) = engine.cancelTask(id)
    override suspend fun restartTask(id: String): TaskEntity {
        engine.restartTask(id)
        return engine.getTask(id)!!
    }
    override suspend fun deleteTask(id: String, deleteFiles: Boolean) =
        engine.deleteTask(id, deleteFiles)

    override suspend fun pauseAll() = engine.pauseAll()
    override suspend fun resumeAll() = engine.resumeAll()
    override suspend fun clearCompleted() = engine.clearCompleted()
}
