package com.ark.chunkdownloader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val url: String,
    val fileName: String,
    val saveDir: String,
    val filePath: String,
    val stagingPath: String,
    val totalSize: Long,
    val loaded: Long,
    val speed: Long,
    val status: String,
    val errorMsg: String?,
    val threads: Int,
    val chunkSize: Int,
    val userAgent: String?,
    val headersJson: String,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?
)

@Entity(
    tableName = "chunks",
    primaryKeys = ["taskId", "index"]
)
data class ChunkEntity(
    val taskId: String,
    val index: Int,
    val start: Long,
    val end: Long,
    val loaded: Long,
    val completed: Boolean,
    val partPath: String
)
