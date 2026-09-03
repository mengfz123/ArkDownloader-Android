package com.ark.chunkdownloader.rpc

import com.ark.chunkdownloader.data.model.AppSettings
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT
import fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RpcServer {
    private var server: NanoHTTPD? = null
    private var token: String = ""
    private var handlers: DownloadRepositoryBridge? = null
    private var bindHost: String = "127.0.0.1"
    private var listenPort: Int = AppSettings.DEFAULT_RPC_PORT
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    fun start(port: Int, rpcToken: String, remote: Boolean, bridge: DownloadRepositoryBridge) {
        stop()
        token = rpcToken.trim()
        handlers = bridge
        bindHost = if (remote) "0.0.0.0" else "127.0.0.1"
        listenPort = port.coerceIn(1, 65535)
        val hostname = if (remote) null else "127.0.0.1"
        server = object : NanoHTTPD(hostname, listenPort) {
            override fun serve(session: IHTTPSession): Response = handle(session)
        }
        try {
            server?.start(SOCKET_READ_TIMEOUT, false)
            _running.value = true
        } catch (e: Exception) {
            _running.value = false
            throw e
        }
    }

    fun stop() {
        runCatching { server?.stop() }
        server = null
        _running.value = false
    }

    fun status(): Map<String, Any?> {
        val remote = bindHost == "0.0.0.0" || bindHost == "::"
        val port = listenPort
        val localUrl = "http://127.0.0.1:$port"
        val lanUrl = if (remote && _running.value) {
            "http://${localLanIp()}:$port"
        } else ""
        return mapOf(
            "running" to _running.value,
            "remote" to remote,
            "port" to port,
            "bindHost" to (if (remote) "0.0.0.0" else "127.0.0.1"),
            "localUrl" to localUrl,
            "lanUrl" to lanUrl,
            "tokenEnabled" to token.isNotBlank()
        )
    }

    private fun localLanIp(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr.isLoopbackAddress || addr is java.net.Inet6Address) continue
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("127.")) continue
                    return host
                }
            }
        } catch (_: Exception) {
        }
        return try {
            InetAddress.getLocalHost().hostAddress ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    private fun handle(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return cors(
                NanoHTTPD.newFixedLengthResponse(
                    Response.Status.OK,
                    NanoHTTPD.MIME_PLAINTEXT,
                    ""
                )
            )
        }
        val path = session.uri.substringBefore('?').trimEnd('/').ifEmpty { "/" }
        // Match pan-fetch: /health is public; /api/* requires token when configured.
        val needsAuth = path.startsWith("/api")
        if (needsAuth && !authed(session.headers)) {
            return cors(gopeedErr("Invalid or missing token", 401, 1001))
        }
        val method = session.method.name
        val body = readBody(session)
        return cors(dispatch(method, path, session.parameters, body))
    }

    private fun authed(headers: Map<String, String>): Boolean {
        if (token.isBlank()) return true
        val lower = headers.mapKeys { it.key.lowercase() }
        val auth = lower["authorization"] ?: ""
        if (auth.equals("Bearer $token", ignoreCase = false) ||
            (auth.startsWith("Bearer ") && auth.removePrefix("Bearer ").trim() == token)
        ) {
            return true
        }
        if (auth == token) return true
        val ark = lower["x-arkdownloader-token"] ?: ""
        if (ark == token) return true
        val pan = lower["x-panfetch-token"] ?: ""
        return pan == token
    }

    private fun readBody(session: IHTTPSession): String {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun dispatch(
        method: String,
        path: String,
        params: Map<String, List<String>>,
        body: String
    ): Response {
        val h = handlers ?: return gopeedErr("服务未就绪", 500)
        return try {
            awaitIo(15_000) {
                route(method, path, params, body, h)
            }
        } catch (e: IllegalArgumentException) {
            gopeedErr(e.message ?: "Bad Request", 400)
        } catch (e: java.util.concurrent.TimeoutException) {
            gopeedErr("请求超时", 504, 1002)
        } catch (e: Exception) {
            gopeedErr(e.message ?: "Internal Server Error", 500)
        }
    }

    private suspend fun route(
        method: String,
        path: String,
        params: Map<String, List<String>>,
        body: String,
        h: DownloadRepositoryBridge
    ): Response {
        // Health / info (Gopeed + pan-fetch)
        if (method == "GET" && path in listOf("/health", "/api/v1/info", "/api/health")) {
            return gopeedOk(h.getInfo())
        }

        // Resolve URL metadata
        if (method == "POST" && path in listOf("/api/v1/resolve", "/api/resolve")) {
            val json = JSONObject(body.ifBlank { "{}" })
            return gopeedOk(h.resolveUrl(json))
        }

        // Config
        if (path in listOf("/api/v1/config", "/api/settings")) {
            when (method) {
                "GET" -> return gopeedOk(h.getSettings())
                "PUT" -> {
                    val json = JSONObject(body.ifBlank { "{}" })
                    return gopeedOk(h.updateSettings(json))
                }
            }
        }

        // Pause / resume all
        if (method == "PUT" && path in listOf("/api/v1/tasks/pause", "/api/tasks/pause")) {
            h.pauseAll()
            return gopeedOk(true)
        }
        if (method == "PUT" && path in listOf("/api/v1/tasks/continue", "/api/tasks/continue")) {
            h.resumeAll()
            return gopeedOk(true)
        }

        // Clear completed
        if (method == "POST" && path in listOf(
                "/api/v1/tasks/clear-completed",
                "/api/tasks/clear-completed"
            )
        ) {
            h.clearCompleted()
            return gopeedOk(true)
        }
        if (method == "DELETE" && path in listOf("/api/v1/tasks", "/api/tasks") &&
            params["id"].isNullOrEmpty()
        ) {
            h.clearCompleted()
            return gopeedOk(true)
        }

        // Batch create
        if (method == "POST" && path in listOf("/api/v1/tasks/batch", "/api/tasks/batch")) {
            val json = JSONObject(body.ifBlank { "{}" })
            return gopeedOk(h.createTasksBatch(json))
        }

        // Task list / create
        if (path in listOf("/api/v1/tasks", "/api/tasks")) {
            when (method) {
                "GET" -> {
                    val status = params["status"]?.firstOrNull()
                    return gopeedOk(h.listTasks(status))
                }
                "POST" -> {
                    val json = JSONObject(body.ifBlank { "{}" })
                    val id = h.createTask(json)
                    return gopeedOk(id)
                }
            }
        }

        // Single task actions
        val v1Match = Regex("^/api/v1/tasks/([^/]+)(?:/(pause|continue))?$").matchEntire(path)
        val legacyMatch = Regex("^/api/tasks/([^/]+)(?:/(pause|resume|continue|cancel|restart))?$")
            .matchEntire(path)
        val match = v1Match ?: legacyMatch
        if (match != null) {
            val id = match.groupValues[1]
            val action = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
            return handleTaskAction(method, id, action, params, h, useV1 = v1Match != null)
        }

        // DELETE by query id
        if (method == "DELETE" && path in listOf("/api/v1/tasks", "/api/tasks")) {
            val id = params["id"]?.firstOrNull()
                ?: return gopeedErr("id required", 400)
            val deleteFiles = params["file"]?.firstOrNull() in listOf("1", "true", "yes") ||
                params["delete_files"]?.firstOrNull() in listOf("1", "true", "on", "yes")
            h.deleteTask(id, deleteFiles)
            return gopeedOk(true)
        }

        return gopeedErr("Not Found", 404)
    }

    private suspend fun handleTaskAction(
        method: String,
        id: String,
        action: String?,
        params: Map<String, List<String>>,
        h: DownloadRepositoryBridge,
        useV1: Boolean
    ): Response {
        val task = h.getTaskEntity(id) ?: return gopeedErr("task not found", 404)
        return when {
            action == null && method == "GET" ->
                gopeedOk(if (useV1) h.taskToV1(task) else h.taskToDict(task))
            action == null && method == "DELETE" -> {
                val deleteFiles = params["file"]?.firstOrNull() in listOf("1", "true", "yes") ||
                    params["delete_files"]?.firstOrNull() in listOf("1", "true", "on", "yes")
                h.deleteTask(id, deleteFiles)
                gopeedOk(true)
            }
            method == "PUT" || method == "POST" -> when (action) {
                "pause" -> {
                    h.pauseTask(id)
                    gopeedOk(if (useV1) id else null, if (useV1) null else "Paused")
                }
                "continue", "resume" -> {
                    h.resumeTask(id)
                    gopeedOk(if (useV1) id else null, if (useV1) null else "Resumed")
                }
                "cancel" -> {
                    h.cancelTask(id)
                    gopeedOk(null, "Cancelled")
                }
                "restart" -> gopeedOk(h.taskToDict(h.restartTask(id)), "Restarted")
                else -> gopeedErr("Not Found", 404)
            }
            else -> gopeedErr("Method Not Allowed", 405)
        }
    }

    /**
     * Bridge suspend work onto IO without blocking forever.
     * NanoHTTPD already runs off the Android main thread; this still caps wait time.
     */
    private fun <T> awaitIo(timeoutMs: Long, block: suspend () -> T): T {
        val latch = CountDownLatch(1)
        val box = AtomicReference<Result<T>>()
        ioScope.launch {
            box.set(runCatching { block() })
            latch.countDown()
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw java.util.concurrent.TimeoutException("RPC handler timed out after ${timeoutMs}ms")
        }
        return box.get()?.getOrThrow()
            ?: throw IllegalStateException("RPC handler returned no result")
    }

    private fun gopeedOk(data: Any?, message: String? = null): Response {
        val obj = JSONObject()
        obj.put("code", 0)
        obj.put("msg", message ?: "")
        obj.put("data", toJsonValue(data))
        // legacy alias fields for old clients
        obj.put("success", true)
        if (message != null) obj.put("message", message)
        return jsonResponse(200, obj)
    }

    private fun gopeedErr(msg: String, http: Int = 400, code: Int = 1000): Response {
        val obj = JSONObject()
        obj.put("code", code)
        obj.put("msg", msg)
        obj.put("data", JSONObject.NULL)
        obj.put("detail", msg)
        obj.put("success", false)
        return jsonResponse(http, obj)
    }

    private fun toJsonValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (k, v) -> put(k.toString(), toJsonValue(v)) }
        }
        is List<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        is Boolean, is Number, is String -> value
        else -> value.toString()
    }

    private fun jsonResponse(code: Int, body: JSONObject): Response {
        val status = when (code) {
            200 -> Response.Status.OK
            400 -> Response.Status.BAD_REQUEST
            401 -> Response.Status.UNAUTHORIZED
            404 -> Response.Status.NOT_FOUND
            405 -> Response.Status.METHOD_NOT_ALLOWED
            504 -> Response.Status.INTERNAL_ERROR
            else -> Response.Status.INTERNAL_ERROR
        }
        return NanoHTTPD.newFixedLengthResponse(
            status,
            "application/json; charset=utf-8",
            body.toString()
        )
    }

    private fun cors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        response.addHeader(
            "Access-Control-Allow-Headers",
            "Content-Type, Authorization, X-ArkDownloader-Token, X-PanFetch-Token"
        )
        response.addHeader("Access-Control-Allow-Private-Network", "true")
        return response
    }

    interface DownloadRepositoryBridge {
        suspend fun getInfo(): Map<String, Any?>
        suspend fun resolveUrl(body: JSONObject): Map<String, Any?>
        suspend fun listTasks(status: String?): List<Map<String, Any?>>
        suspend fun getTaskEntity(id: String): com.ark.chunkdownloader.data.db.TaskEntity?
        fun taskToDict(task: com.ark.chunkdownloader.data.db.TaskEntity): Map<String, Any?>
        suspend fun taskToV1(task: com.ark.chunkdownloader.data.db.TaskEntity): Map<String, Any?>
        /** Returns created task id (Gopeed-style). */
        suspend fun createTask(body: JSONObject): String
        suspend fun createTasksBatch(body: JSONObject): Map<String, Any?>
        suspend fun pauseTask(id: String)
        suspend fun resumeTask(id: String)
        suspend fun cancelTask(id: String)
        suspend fun restartTask(id: String): com.ark.chunkdownloader.data.db.TaskEntity
        suspend fun deleteTask(id: String, deleteFiles: Boolean)
        suspend fun pauseAll()
        suspend fun resumeAll()
        suspend fun clearCompleted()
        suspend fun getSettings(): Map<String, Any?>
        suspend fun updateSettings(body: JSONObject): Map<String, Any?>
    }
}
