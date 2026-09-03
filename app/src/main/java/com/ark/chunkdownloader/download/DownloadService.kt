package com.ark.chunkdownloader.download

import android.content.Intent
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ark.chunkdownloader.ArkApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadService : LifecycleService() {
    private lateinit var notifier: DownloadNotifier
    private var watchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notifier = DownloadNotifier(this)
        notifier.ensureChannel()
        startForeground(
            DownloadNotifier.NOTIFICATION_ID,
            notifier.buildForegroundNotification(0, "服务已启动")
        )
        watchJob = lifecycleScope.launch {
            ArkApp.repository.engine.activeCount.collectLatest { count ->
                val note = if (count > 0) "下载进行中" else "等待任务"
                val n = notifier.buildForegroundNotification(count, note)
                startForeground(DownloadNotifier.NOTIFICATION_ID, n)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        watchJob?.cancel()
        super.onDestroy()
    }
}
