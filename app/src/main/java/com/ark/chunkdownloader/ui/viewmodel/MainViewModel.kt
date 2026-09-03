package com.ark.chunkdownloader.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ark.chunkdownloader.ArkApp
import com.ark.chunkdownloader.data.model.AppSettings
import com.ark.chunkdownloader.data.model.DownloadTask
import com.ark.chunkdownloader.download.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo: DownloadRepository = (app as ArkApp).repository

    val tasks: StateFlow<List<DownloadTask>> = repo.tasksFlow
    val settings: StateFlow<AppSettings> = repo.settingsFlow
    val rpcRunning: StateFlow<Boolean> = repo.rpcRunning

    fun createTask(url: String, fileName: String?, threads: Int, chunkSize: Int, headersJson: String) {
        createTasks(listOf(url), fileName, threads, chunkSize, headersJson)
    }

    /** Multi-URL paste: one URL per line (blank lines ignored). */
    fun createTasks(
        paste: String,
        fileName: String?,
        threads: Int,
        chunkSize: Int,
        headersJson: String
    ) {
        createTasks(parseUrlPaste(paste), fileName, threads, chunkSize, headersJson)
    }

    fun createTasks(
        urls: List<String>,
        fileName: String?,
        threads: Int,
        chunkSize: Int,
        headersJson: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.createTasksFromUrls(urls, fileName, threads, chunkSize, headersJson)
        }
    }

    companion object {
        fun parseUrlPaste(paste: String): List<String> =
            paste.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()
    }

    fun pauseTask(task: DownloadTask) =
        viewModelScope.launch(Dispatchers.IO) { repo.engine.pauseTask(task.id) }
    fun resumeTask(task: DownloadTask) =
        viewModelScope.launch(Dispatchers.IO) { repo.engine.resumeTask(task.id) }
    fun cancelTask(task: DownloadTask) =
        viewModelScope.launch(Dispatchers.IO) { repo.engine.cancelTask(task.id) }
    fun restartTask(task: DownloadTask) =
        viewModelScope.launch(Dispatchers.IO) { repo.engine.restartTask(task.id) }
    fun deleteTask(task: DownloadTask, deleteFiles: Boolean) =
        viewModelScope.launch(Dispatchers.IO) { repo.engine.deleteTask(task.id, deleteFiles) }

    fun pauseAll() = viewModelScope.launch(Dispatchers.IO) { repo.engine.pauseAll() }
    fun resumeAll() = viewModelScope.launch(Dispatchers.IO) { repo.engine.resumeAll() }
    fun clearCompleted() = viewModelScope.launch(Dispatchers.IO) { repo.engine.clearCompleted() }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch(Dispatchers.IO) { repo.settingsRepo.update(transform) }
    }
}
