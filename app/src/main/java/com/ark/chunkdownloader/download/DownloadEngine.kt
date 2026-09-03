package com.ark.chunkdownloader.download

import android.content.Context
import com.ark.chunkdownloader.data.db.AppDatabase
import com.ark.chunkdownloader.data.db.ChunkEntity
import com.ark.chunkdownloader.data.db.TaskEntity
import com.ark.chunkdownloader.data.model.AppSettings
import com.ark.chunkdownloader.data.model.TaskStatus
import com.ark.chunkdownloader.data.prefs.SettingsRepository
import com.ark.chunkdownloader.util.FilePublish
import com.ark.chunkdownloader.util.FormatUtil
import com.ark.chunkdownloader.util.UrlResolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * High-throughput multi-connection engine.
 *
 * - Baidu PCS/CDN: size/fin from URL, netdisk UA, up to 8 workers
 * - Direct Range writes into one pre-allocated final file (no .partN merge / post-copy)
 * - Sidecar checkpoint of completed chunk indices for resume
 * - Pause cancels in-flight OkHttp Calls and clears speed
 * - All work on [Dispatchers.IO]; UI observes Room / StateFlow
 */
class DownloadEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val settingsRepo: SettingsRepository,
    private val downloader: ChunkDownloader = ChunkDownloader()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val runtimes = ConcurrentHashMap<String, TaskRuntime>()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount

    fun observeTasks() = db.taskDao().observeAll()

    suspend fun snapshot(): List<TaskEntity> = db.taskDao().getAll()

    suspend fun getTask(id: String) = db.taskDao().getById(id)

    suspend fun createTask(
        url: String,
        fileName: String?,
        saveDir: String?,
        threads: Int?,
        chunkSize: Int?,
        totalSize: Long?,
        userAgent: String?,
        headers: Map<String, String>?
    ): TaskEntity {
        val settings = settingsRepo.current()
        val resolved = UrlResolve.resolve(
            url = url,
            nameHint = fileName,
            sizeHint = totalSize ?: 0L
        )
        val kind = resolved.kind
        val id = UUID.randomUUID().toString()
        val dir = FilePublish.resolveWritableDir(
            context,
            saveDir?.takeIf { it.isNotBlank() } ?: settings.defaultSaveDir.ifBlank { null }
        )
        val mergedHeaders = FormatUtil.parseHeaders(settings.defaultHeadersJson).toMutableMap()
        headers?.forEach { mergedHeaders[it.key] = it.value }
        val ua = UrlResolve.pickUserAgent(kind, settings, userAgent)
        val th = (threads ?: settings.maxThreads).coerceIn(1, AppSettings.MAX_THREADS)
        val requestedChunk = chunkSize ?: settings.chunkSize
        val cs = UrlResolve.chunkBytes(requestedChunk, kind)

        var total = resolved.size
        var resolvedName = resolved.name
        if (kind == UrlResolve.Kind.HTTP) {
            // Quark/OSS: probe Content-Disposition; always merge with URL query name.
            val needSize = total <= 0
            val forceName = fileName.isNullOrBlank() || UrlResolve.isQuarkOrCdnUrl(resolved.url)
            var contentDisposition: String? = null
            if (needSize || forceName) {
                val probe = downloader.probe(
                    url = resolved.url,
                    headers = mergedHeaders,
                    userAgent = ua,
                    knownSize = if (needSize) -1L else total,
                    skipHead = false,
                    forceHeaders = forceName
                )
                if (needSize && probe.totalSize > 0) total = probe.totalSize
                contentDisposition = probe.contentDisposition
            }
            if (fileName.isNullOrBlank()) {
                resolvedName = UrlResolve.resolveHttpFileName(
                    url = resolved.url,
                    contentDisposition = contentDisposition,
                    nameHint = null
                )
            } else if (UrlResolve.isQuarkOrCdnUrl(resolved.url)) {
                // RPC/UI may pass a Latin-1 mojibake name — prefer URL/CD.
                val better = UrlResolve.resolveHttpFileName(
                    url = resolved.url,
                    contentDisposition = contentDisposition,
                    nameHint = null
                )
                val display = UrlResolve.displayFileName(fileName, resolved.url)
                resolvedName = when {
                    better.isNotBlank() && better != "download.bin" &&
                        display == better -> better
                    display != fileName && display.isNotBlank() -> display
                    else -> UrlResolve.sanitizeFileName(
                        UrlResolve.decodeEncodedName(fileName)
                    )
                }
            }
        }
        if (total <= 0) {
            throw IllegalArgumentException("无法获取文件大小，请检查链接是否有效")
        }

        // Direct-to-final path — stagingPath == filePath (no post-download full copy)
        val uniqueName = uniqueFileName(dir, resolvedName)
        val outFile = File(dir, uniqueName)
        val initialStatus = if (settings.autoStart) TaskStatus.PENDING else TaskStatus.PAUSED
        val entity = TaskEntity(
            id = id,
            url = resolved.url,
            fileName = uniqueName,
            saveDir = dir.absolutePath,
            filePath = outFile.absolutePath,
            stagingPath = outFile.absolutePath,
            totalSize = total,
            loaded = 0,
            speed = 0,
            status = initialStatus.name,
            errorMsg = null,
            threads = th,
            chunkSize = cs,
            userAgent = ua,
            headersJson = FormatUtil.headersToJson(mergedHeaders),
            createdAt = System.currentTimeMillis(),
            startedAt = null,
            completedAt = null
        )
        db.taskDao().upsert(entity)
        prepareChunks(entity)
        if (settings.autoStart) {
            schedule(entity.id)
        }
        return entity
    }

    private fun uniqueFileName(dir: File, desired: String): String {
        val safe = UrlResolve.sanitizeFileName(desired.ifBlank { "download.bin" })
        if (!File(dir, safe).exists()) return safe
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var i = 1
        while (i < 10_000) {
            val name = "$base ($i)$ext"
            if (!File(dir, name).exists()) return name
            i++
        }
        return "$base-${System.currentTimeMillis()}$ext"
    }

    private suspend fun prepareChunks(task: TaskEntity) {
        val existing = db.taskDao().getChunks(task.id)
        if (existing.isNotEmpty()) return
        val ranges = ChunkDownloader.buildChunks(task.totalSize, task.chunkSize)
        val chunks = ranges.mapIndexed { index, range ->
            ChunkEntity(
                taskId = task.id,
                index = index,
                start = range.first,
                end = range.last,
                loaded = 0,
                completed = false,
                partPath = ""
            )
        }
        db.taskDao().upsertChunks(chunks)
        writeSidecar(task, emptySet())
    }

    fun schedule(taskId: String) {
        if (jobs.containsKey(taskId)) return
        val job = scope.launch {
            try {
                runTask(taskId)
            } finally {
                jobs.remove(taskId)
                runtimes.remove(taskId)
                _activeCount.value = jobs.size
                pumpQueue()
            }
        }
        jobs[taskId] = job
        _activeCount.value = jobs.size
    }

    private fun pumpQueue() {
        scope.launch {
            val settings = settingsRepo.current()
            val running = jobs.size
            if (running >= settings.maxConcurrentTasks) return@launch
            val waiting = db.taskDao().getAll()
                .filter { it.status == TaskStatus.PENDING.name && !jobs.containsKey(it.id) }
            for (t in waiting) {
                if (jobs.size >= settings.maxConcurrentTasks) break
                schedule(t.id)
            }
        }
    }

    suspend fun pauseTask(id: String) {
        val rt = runtimes[id]
        rt?.pauseRequested?.set(true)
        rt?.cancelAllCalls()
        rt?.speed?.clear()
        jobs[id]?.cancel()
        jobs.remove(id)
        runtimes.remove(id)
        val task = db.taskDao().getById(id) ?: return
        db.taskDao().upsert(task.copy(status = TaskStatus.PAUSED.name, speed = 0, errorMsg = null))
        _activeCount.value = jobs.size
        pumpQueue()
    }

    suspend fun resumeTask(id: String) {
        val task = db.taskDao().getById(id) ?: return
        if (task.status == TaskStatus.COMPLETED.name) return
        db.taskDao().upsert(
            task.copy(status = TaskStatus.PENDING.name, errorMsg = null, speed = 0)
        )
        schedule(id)
    }

    suspend fun cancelTask(id: String) {
        val rt = runtimes[id]
        rt?.cancelRequested?.set(true)
        rt?.pauseRequested?.set(true)
        rt?.cancelAllCalls()
        jobs[id]?.cancel()
        jobs.remove(id)
        runtimes.remove(id)
        updateStatus(id, TaskStatus.CANCELED)
        _activeCount.value = jobs.size
        pumpQueue()
    }

    suspend fun restartTask(id: String) {
        val task = db.taskDao().getById(id) ?: return
        runtimes[id]?.cancelAllCalls()
        jobs[id]?.cancel()
        jobs.remove(id)
        runtimes.remove(id)
        deleteSidecar(task)
        File(task.stagingPath).delete()
        if (task.filePath != task.stagingPath) File(task.filePath).delete()
        db.taskDao().deleteChunks(id)
        val reset = task.copy(
            loaded = 0,
            speed = 0,
            status = TaskStatus.PENDING.name,
            errorMsg = null,
            startedAt = null,
            completedAt = null
        )
        db.taskDao().upsert(reset)
        prepareChunks(reset)
        schedule(id)
    }

    suspend fun deleteTask(id: String, deleteFiles: Boolean) {
        cancelTask(id)
        val task = db.taskDao().getById(id)
        if (task != null) {
            deleteSidecar(task)
            if (deleteFiles) {
                File(task.stagingPath).delete()
                File(task.filePath).delete()
            }
        }
        db.taskDao().deleteTaskFully(id)
    }

    suspend fun pauseAll() {
        snapshot().filter {
            it.status == TaskStatus.DOWNLOADING.name ||
                it.status == TaskStatus.PENDING.name ||
                it.status == TaskStatus.MERGING.name
        }.forEach { pauseTask(it.id) }
    }

    suspend fun resumeAll() {
        snapshot().filter {
            it.status == TaskStatus.PAUSED.name ||
                it.status == TaskStatus.FAILED.name
        }.forEach { resumeTask(it.id) }
    }

    suspend fun clearCompleted() {
        snapshot().filter { it.status == TaskStatus.COMPLETED.name }
            .forEach { deleteTask(it.id, deleteFiles = false) }
    }

    private suspend fun updateStatus(id: String, status: TaskStatus, error: String? = null) {
        val task = db.taskDao().getById(id) ?: return
        db.taskDao().upsert(
            task.copy(
                status = status.name,
                errorMsg = error,
                speed = if (status == TaskStatus.DOWNLOADING) task.speed else 0
            )
        )
    }

    private suspend fun runTask(taskId: String) {
        val settings = settingsRepo.current()
        while (true) {
            val runningOthers = jobs.count { (id, job) -> id != taskId && job.isActive }
            if (runningOthers < settings.maxConcurrentTasks) break
            val task = db.taskDao().getById(taskId) ?: return
            if (task.status == TaskStatus.PAUSED.name || task.status == TaskStatus.CANCELED.name) return
            delay(200)
        }

        var task = db.taskDao().getById(taskId) ?: return
        if (task.status == TaskStatus.COMPLETED.name) return
        if (task.status == TaskStatus.PAUSED.name || task.status == TaskStatus.CANCELED.name) return

        val headers = FormatUtil.parseHeaders(task.headersJson)
        val kind = if (UrlResolve.isBaiduUrl(task.url)) UrlResolve.Kind.BAIDU else UrlResolve.Kind.HTTP
        val ua = task.userAgent?.takeIf { it.isNotBlank() }
            ?: UrlResolve.pickUserAgent(kind, settings)

        val rt = TaskRuntime()
        runtimes[taskId] = rt

        db.taskDao().upsert(
            task.copy(
                status = TaskStatus.DOWNLOADING.name,
                startedAt = task.startedAt ?: System.currentTimeMillis(),
                errorMsg = null,
                userAgent = ua,
                speed = 0
            )
        )
        task = db.taskDao().getById(taskId) ?: return

        if (task.totalSize <= 0) {
            if (kind == UrlResolve.Kind.BAIDU) {
                val fromUrl = UrlResolve.parseBaiduSize(task.url)
                if (fromUrl > 0) {
                    task = task.copy(totalSize = fromUrl)
                    db.taskDao().upsert(task)
                }
            } else {
                val probe = downloader.probe(task.url, headers, ua, skipHead = false)
                if (probe.totalSize > 0) {
                    task = task.copy(totalSize = probe.totalSize)
                    db.taskDao().upsert(task)
                }
            }
            if (task.totalSize > 0) {
                db.taskDao().deleteChunks(taskId)
                prepareChunks(task)
            }
        }

        val chunkSize = UrlResolve.chunkBytes(task.chunkSize, kind)
        if (chunkSize != task.chunkSize) {
            task = task.copy(chunkSize = chunkSize)
            db.taskDao().upsert(task)
            db.taskDao().deleteChunks(taskId)
            prepareChunks(task)
        }

        restoreFromSidecar(task)

        var chunks = db.taskDao().getChunks(taskId)
        if (chunks.isEmpty() && task.totalSize > 0) {
            prepareChunks(task)
            chunks = db.taskDao().getChunks(taskId)
        }
        if (chunks.isEmpty()) {
            fail(taskId, "无法确定文件大小或不支持分片下载")
            return
        }

        ensurePreallocated(File(task.stagingPath), task.totalSize)

        val completed = ConcurrentHashMap.newKeySet<Int>()
        chunks.filter { it.completed }.forEach { completed.add(it.index) }
        rt.loaded.set(chunks.filter { it.completed }.sumOf { it.end - it.start + 1 })
        persistProgress(taskId, rt, force = true)

        val pendingChunks = chunks.filter { !it.completed }.sortedBy { it.index }
        if (pendingChunks.isEmpty()) {
            finishTask(taskId)
            return
        }

        // Baidu: up to 8 connections (not hard-capped at 4); HTTP: up to 16
        val workers = if (kind == UrlResolve.Kind.BAIDU) {
            task.threads.coerceIn(1, 8)
        } else {
            task.threads.coerceIn(1, AppSettings.MAX_THREADS)
        }

        val queue = ConcurrentLinkedQueue(pendingChunks)
        val checkpointMutex = Mutex()
        val fatalError = AtomicReference<Exception?>(null)
        val stagingFile = File(task.stagingPath)

        val progressJob = scope.launch {
            while (isActive && !rt.pauseRequested.get() && !rt.cancelRequested.get()) {
                delay(PROGRESS_INTERVAL_MS)
                rt.speed.sample()
                flushSidecarIfDirty(task, completed, rt, force = false)
                persistProgress(taskId, rt, force = false)
            }
        }

        try {
            // Per-worker RAF/channel — avoids sharing one FileChannel across threads.
            coroutineScope {
                (0 until workers).map {
                    async(Dispatchers.IO) {
                        RandomAccessFile(stagingFile, "rw").use { raf ->
                            val channel = raf.channel
                            while (true) {
                                if (rt.pauseRequested.get() || rt.cancelRequested.get()) return@async
                                if (fatalError.get() != null) return@async
                                val chunk = queue.poll() ?: return@async
                                try {
                                    downloadChunkDirect(
                                        task = task,
                                        chunk = chunk,
                                        headers = headers,
                                        ua = ua,
                                        channel = channel,
                                        rt = rt,
                                        completed = completed,
                                        checkpointMutex = checkpointMutex
                                    )
                                } catch (e: InterruptedIOException) {
                                    return@async
                                } catch (e: CancellationException) {
                                    return@async
                                } catch (e: Exception) {
                                    if (rt.pauseRequested.get() || rt.cancelRequested.get()) return@async
                                    fatalError.compareAndSet(null, e)
                                    return@async
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            if (rt.cancelRequested.get()) return
            if (rt.pauseRequested.get()) {
                flushSidecarIfDirty(task, completed, rt, force = true)
                persistProgress(taskId, rt, force = true, speedOverride = 0)
                updateStatus(taskId, TaskStatus.PAUSED)
                return
            }

            fatalError.get()?.let { throw it }

            val left = db.taskDao().getChunks(taskId).any { !it.completed }
            if (left) {
                flushSidecarIfDirty(task, completed, rt, force = true)
                persistProgress(taskId, rt, force = true, speedOverride = 0)
                updateStatus(taskId, TaskStatus.PAUSED)
                return
            }
            finishTask(taskId)
        } catch (e: CancellationException) {
            flushSidecarIfDirty(task, completed, rt, force = true)
            persistProgress(taskId, rt, force = true, speedOverride = 0)
            if (rt.pauseRequested.get()) {
                updateStatus(taskId, TaskStatus.PAUSED)
            }
            throw e
        } catch (e: Exception) {
            if (rt.pauseRequested.get() || e is InterruptedIOException) {
                flushSidecarIfDirty(task, completed, rt, force = true)
                persistProgress(taskId, rt, force = true, speedOverride = 0)
                updateStatus(taskId, TaskStatus.PAUSED)
            } else {
                fail(taskId, e.message ?: e.toString())
            }
        } finally {
            progressJob.cancel()
            rt.cancelAllCalls()
            rt.speed.clear()
            flushSidecarIfDirty(task, completed, rt, force = true)
            persistProgress(taskId, rt, force = true, speedOverride = 0)
        }
    }

    private suspend fun downloadChunkDirect(
        task: TaskEntity,
        chunk: ChunkEntity,
        headers: Map<String, String>,
        ua: String?,
        channel: java.nio.channels.FileChannel,
        rt: TaskRuntime,
        completed: MutableSet<Int>,
        checkpointMutex: Mutex
    ) {
        val expected = chunk.end - chunk.start + 1
        if (expected <= 0) return
        if (chunk.completed || chunk.index in completed) return

        var attempt = 0
        while (attempt < MAX_CHUNK_RETRIES) {
            if (rt.pauseRequested.get() || rt.cancelRequested.get()) return
            attempt++
            val flightBefore = AtomicLong(0)
            try {
                val written = downloader.downloadRange(
                    url = task.url,
                    range = chunk.start..chunk.end,
                    channel = channel,
                    writeOffset = chunk.start,
                    headers = headers,
                    userAgent = ua,
                    isCancelled = { rt.pauseRequested.get() || rt.cancelRequested.get() },
                    onCall = { call ->
                        rt.registerCall(call)
                        if (rt.pauseRequested.get() || rt.cancelRequested.get()) {
                            call.cancel()
                        }
                    },
                    onCallDone = { call -> rt.unregisterCall(call) },
                    onBytes = { n ->
                        flightBefore.addAndGet(n)
                        rt.speed.add(n)
                        rt.inFlightBytes.addAndGet(n)
                    }
                )
                if (rt.pauseRequested.get() || rt.cancelRequested.get()) {
                    rt.inFlightBytes.addAndGet(-flightBefore.get())
                    return
                }
                if (written != expected) {
                    throw IllegalStateException("分片长度不符 $written != $expected")
                }
                rt.inFlightBytes.addAndGet(-written)
                rt.loaded.addAndGet(expected)
                checkpointMutex.withLock {
                    db.taskDao().upsertChunks(
                        listOf(chunk.copy(loaded = expected, completed = true))
                    )
                    completed.add(chunk.index)
                    rt.sidecarDirty.set(true)
                }
                return
            } catch (e: InterruptedIOException) {
                rt.inFlightBytes.addAndGet(-flightBefore.get())
                return
            } catch (e: CancellationException) {
                rt.inFlightBytes.addAndGet(-flightBefore.get())
                return
            } catch (e: Exception) {
                rt.inFlightBytes.addAndGet(-flightBefore.get())
                if (rt.pauseRequested.get() || rt.cancelRequested.get()) return
                if (attempt >= MAX_CHUNK_RETRIES) throw e
                delay(400L * attempt)
            }
        }
    }

    private suspend fun persistProgress(
        taskId: String,
        rt: TaskRuntime,
        force: Boolean,
        speedOverride: Long? = null
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - rt.lastPersistMs.get() < PROGRESS_INTERVAL_MS) return
        rt.lastPersistMs.set(now)
        val task = db.taskDao().getById(taskId) ?: return
        if (task.status != TaskStatus.DOWNLOADING.name &&
            task.status != TaskStatus.PENDING.name
        ) {
            if (!force) return
        }
        val speed = speedOverride ?: rt.speed.speedBps
        val loadedNow = (rt.loaded.get() + rt.inFlightBytes.get().coerceAtLeast(0))
            .coerceIn(0, max(task.totalSize, 0))
        db.taskDao().upsert(
            task.copy(
                loaded = loadedNow,
                speed = speed
            )
        )
    }

    private suspend fun finishTask(taskId: String) {
        val task = db.taskDao().getById(taskId) ?: return
        // Brief MERGING for UI parity with mobile; file is already on final path.
        db.taskDao().upsert(task.copy(status = TaskStatus.MERGING.name, speed = 0))

        val out = File(task.filePath.ifBlank { task.stagingPath })
        if (!out.exists() || (task.totalSize > 0 && out.length() != task.totalSize)) {
            throw IllegalStateException("文件大小校验失败")
        }
        val incomplete = db.taskDao().getChunks(taskId).any { !it.completed }
        if (incomplete) throw IllegalStateException("仍有未完成分片")

        FilePublish.scanFile(context, out)

        db.taskDao().upsert(
            task.copy(
                status = TaskStatus.COMPLETED.name,
                filePath = out.absolutePath,
                stagingPath = out.absolutePath,
                loaded = task.totalSize,
                completedAt = System.currentTimeMillis(),
                errorMsg = null,
                speed = 0
            )
        )
        deleteSidecar(task)

        if (settingsRepo.current().notifyOnComplete) {
            DownloadNotifier(context).notifyCompleted(task.fileName)
        }
    }

    private suspend fun fail(taskId: String, message: String) {
        val task = db.taskDao().getById(taskId) ?: return
        db.taskDao().upsert(
            task.copy(
                status = TaskStatus.FAILED.name,
                errorMsg = message,
                speed = 0
            )
        )
    }

    suspend fun resumePendingOnBoot() {
        val all = db.taskDao().getAll()
        all.filter { it.status == TaskStatus.DOWNLOADING.name || it.status == TaskStatus.MERGING.name }
            .forEach { t ->
                db.taskDao().upsert(t.copy(status = TaskStatus.PAUSED.name, speed = 0))
            }
        if (settingsRepo.current().autoStart) {
            all.filter { it.status == TaskStatus.PENDING.name }.forEach { schedule(it.id) }
        }
    }

    private fun ensurePreallocated(file: File, size: Long) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() != size) {
                raf.setLength(size)
            }
        }
    }

    private fun sidecarFile(task: TaskEntity): File =
        File(task.stagingPath + ".arkmeta.json")

    private fun writeSidecar(task: TaskEntity, completed: Set<Int>) {
        val obj = JSONObject()
            .put("id", task.id)
            .put("url", task.url)
            .put("name", task.fileName)
            .put("size", task.totalSize)
            .put("chunkSize", task.chunkSize)
            .put("completed_chunks", JSONArray(completed.sorted()))
        sidecarFile(task).writeText(obj.toString())
    }

    /** Throttled sidecar flush (~200ms) — avoids per-chunk disk contention with Range writes. */
    private fun flushSidecarIfDirty(
        task: TaskEntity,
        completed: Set<Int>,
        rt: TaskRuntime,
        force: Boolean
    ) {
        if (!force) {
            if (!rt.sidecarDirty.get()) return
            val now = System.currentTimeMillis()
            if (now - rt.lastSidecarMs.get() < PROGRESS_INTERVAL_MS) return
            if (!rt.sidecarDirty.compareAndSet(true, false)) return
            rt.lastSidecarMs.set(now)
        } else {
            rt.sidecarDirty.set(false)
            rt.lastSidecarMs.set(System.currentTimeMillis())
        }
        runCatching { writeSidecar(task, completed) }
    }

    private suspend fun restoreFromSidecar(task: TaskEntity) {
        val sc = sidecarFile(task)
        if (!sc.exists()) return
        try {
            val data = JSONObject(sc.readText())
            if (data.optLong("size") != task.totalSize) return
            if (data.optString("url") != task.url) return
            val arr = data.optJSONArray("completed_chunks") ?: return
            val done = (0 until arr.length()).map { arr.getInt(it) }.toSet()
            if (done.isEmpty()) return
            val chunks = db.taskDao().getChunks(task.id)
            val updated = chunks.map { c ->
                if (c.index in done) c.copy(loaded = c.end - c.start + 1, completed = true) else c
            }
            db.taskDao().upsertChunks(updated)
            val loaded = updated.filter { it.completed }.sumOf { it.end - it.start + 1 }
            db.taskDao().upsert(task.copy(loaded = loaded))
        } catch (_: Exception) {
            // ignore corrupt sidecar
        }
    }

    private fun deleteSidecar(task: TaskEntity) {
        sidecarFile(task).delete()
    }

    private class TaskRuntime {
        val pauseRequested = AtomicBoolean(false)
        val cancelRequested = AtomicBoolean(false)
        val loaded = AtomicLong(0)
        /** Bytes written in incomplete in-flight chunks (smooth progress UI). */
        val inFlightBytes = AtomicLong(0)
        val speed = SpeedTracker()
        val lastPersistMs = AtomicLong(0)
        val lastSidecarMs = AtomicLong(0)
        val sidecarDirty = AtomicBoolean(false)
        private val calls = ConcurrentHashMap.newKeySet<Call>()

        fun registerCall(call: Call) {
            calls.add(call)
        }

        fun unregisterCall(call: Call) {
            calls.remove(call)
        }

        fun cancelAllCalls() {
            calls.forEach { runCatching { it.cancel() } }
            calls.clear()
        }
    }

    /** Bytes in AtomicLong + peak sliding window (~1s). Exposes [speedBps]. */
    class SpeedTracker {
        private val windowBytes = AtomicLong(0)
        private val windowStartNs = AtomicLong(System.nanoTime())
        @Volatile
        var speedBps: Long = 0
            private set
        @Volatile
        var peakBps: Long = 0
            private set

        fun add(n: Long) {
            windowBytes.addAndGet(n)
            sample()
        }

        fun sample(): Long {
            val now = System.nanoTime()
            val start = windowStartNs.get()
            val elapsedNs = now - start
            if (elapsedNs >= 1_000_000_000L) {
                if (windowStartNs.compareAndSet(start, now)) {
                    val bytes = windowBytes.getAndSet(0)
                    val bps = (bytes * 1_000_000_000L) / elapsedNs.coerceAtLeast(1)
                    speedBps = bps
                    if (bps > peakBps) peakBps = bps
                }
            }
            return speedBps
        }

        fun clear() {
            windowBytes.set(0)
            windowStartNs.set(System.nanoTime())
            speedBps = 0
        }
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 200L
        private const val MAX_CHUNK_RETRIES = 8
    }
}
