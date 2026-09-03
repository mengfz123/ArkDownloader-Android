package com.ark.chunkdownloader.data

import com.ark.chunkdownloader.data.db.ChunkEntity
import com.ark.chunkdownloader.data.db.TaskEntity
import com.ark.chunkdownloader.data.model.DownloadTask
import com.ark.chunkdownloader.data.model.TaskStatus

fun TaskEntity.toModel(): DownloadTask = DownloadTask(
    id = id,
    url = url,
    fileName = fileName,
    saveDir = saveDir,
    filePath = filePath,
    totalSize = totalSize,
    loaded = loaded,
    speed = speed,
    status = runCatching { TaskStatus.valueOf(status) }.getOrNull()
        ?: TaskStatus.fromApi(status)
        ?: TaskStatus.PENDING,
    errorMsg = errorMsg,
    threads = threads,
    chunkSize = chunkSize,
    userAgent = userAgent,
    headersJson = headersJson,
    createdAt = createdAt,
    startedAt = startedAt,
    completedAt = completedAt
)

fun DownloadTask.toEntity(stagingPath: String = ""): TaskEntity = TaskEntity(
    id = id,
    url = url,
    fileName = fileName,
    saveDir = saveDir,
    filePath = filePath,
    stagingPath = stagingPath,
    totalSize = totalSize,
    loaded = loaded,
    speed = speed,
    status = status.name,
    errorMsg = errorMsg,
    threads = threads,
    chunkSize = chunkSize,
    userAgent = userAgent,
    headersJson = headersJson,
    createdAt = createdAt,
    startedAt = startedAt,
    completedAt = completedAt
)
