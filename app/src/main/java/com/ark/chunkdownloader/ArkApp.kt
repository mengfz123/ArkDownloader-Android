package com.ark.chunkdownloader

import android.app.Application
import com.ark.chunkdownloader.data.db.AppDatabase
import com.ark.chunkdownloader.data.prefs.SettingsRepository
import com.ark.chunkdownloader.download.DownloadEngine
import com.ark.chunkdownloader.download.DownloadRepository
import com.ark.chunkdownloader.rpc.RpcServer

class ArkApp : Application() {
    lateinit var repository: DownloadRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val db = AppDatabase.get(this)
        val settingsRepo = SettingsRepository(this)
        val engine = DownloadEngine(this, db, settingsRepo)
        val rpc = RpcServer()
        repository = DownloadRepository(this, settingsRepo, engine, rpc)
        repository.initialize()
    }

    companion object {
        @Volatile
        private var instance: ArkApp? = null

        val repository: DownloadRepository
            get() = instance?.repository
                ?: error("ArkApp not initialized")
    }
}
