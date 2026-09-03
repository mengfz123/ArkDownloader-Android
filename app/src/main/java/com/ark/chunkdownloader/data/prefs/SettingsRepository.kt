package com.ark.chunkdownloader.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ark.chunkdownloader.data.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("ark_settings")

class SettingsRepository(private val context: Context) {
    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settingsFlow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(current()).normalized()
        context.dataStore.edit { prefs ->
            prefs[SAVE_DIR] = next.defaultSaveDir
            prefs[MAX_THREADS] = next.maxThreads
            prefs[MAX_CONCURRENT] = next.maxConcurrentTasks
            prefs[CHUNK_SIZE] = next.chunkSize
            prefs[AUTO_START] = next.autoStart
            prefs[NOTIFY_ON_COMPLETE] = next.notifyOnComplete
            prefs[USER_AGENT] = next.userAgent
            prefs[HTTP_USER_AGENT] = next.httpUserAgent
            prefs[HEADERS_JSON] = next.defaultHeadersJson
            prefs[RPC_ENABLED] = next.rpcEnabled
            prefs[RPC_PORT] = next.rpcPort
            prefs[RPC_REMOTE] = next.rpcRemote
            prefs[RPC_TOKEN] = next.rpcToken
        }
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        defaultSaveDir = this[SAVE_DIR] ?: "",
        maxThreads = (this[MAX_THREADS] ?: AppSettings.DEFAULT_CONNECTIONS)
            .coerceIn(1, AppSettings.MAX_THREADS),
        maxConcurrentTasks = (this[MAX_CONCURRENT] ?: AppSettings.DEFAULT_MAX_RUNNING)
            .coerceIn(1, AppSettings.MAX_RUNNING),
        chunkSize = (this[CHUNK_SIZE] ?: AppSettings.DEFAULT_CHUNK)
            .coerceIn(AppSettings.MIN_CHUNK, AppSettings.MAX_CHUNK),
        autoStart = this[AUTO_START] ?: true,
        notifyOnComplete = this[NOTIFY_ON_COMPLETE] ?: true,
        userAgent = this[USER_AGENT] ?: AppSettings.BAIDU_UA,
        httpUserAgent = this[HTTP_USER_AGENT] ?: AppSettings.DEFAULT_HTTP_USER_AGENT,
        defaultHeadersJson = this[HEADERS_JSON] ?: "{}",
        rpcEnabled = this[RPC_ENABLED] ?: true,
        rpcPort = this[RPC_PORT] ?: AppSettings.DEFAULT_RPC_PORT,
        rpcRemote = this[RPC_REMOTE] ?: false,
        rpcToken = this[RPC_TOKEN] ?: ""
    ).normalized()

    companion object {
        private val SAVE_DIR = stringPreferencesKey("save_dir")
        private val MAX_CONCURRENT = intPreferencesKey("max_concurrent")
        private val MAX_THREADS = intPreferencesKey("max_threads")
        private val CHUNK_SIZE = intPreferencesKey("chunk_size")
        private val AUTO_START = booleanPreferencesKey("auto_start")
        private val NOTIFY_ON_COMPLETE = booleanPreferencesKey("notify_on_complete")
        private val USER_AGENT = stringPreferencesKey("user_agent")
        private val HTTP_USER_AGENT = stringPreferencesKey("http_user_agent")
        private val HEADERS_JSON = stringPreferencesKey("headers_json")
        private val RPC_ENABLED = booleanPreferencesKey("rpc_enabled")
        private val RPC_PORT = intPreferencesKey("rpc_port")
        private val RPC_REMOTE = booleanPreferencesKey("rpc_remote")
        private val RPC_TOKEN = stringPreferencesKey("rpc_token")
    }
}

private fun AppSettings.normalized(): AppSettings = copy(
    maxThreads = maxThreads.coerceIn(1, AppSettings.MAX_THREADS),
    maxConcurrentTasks = maxConcurrentTasks.coerceIn(1, AppSettings.MAX_RUNNING),
    chunkSize = chunkSize.coerceIn(AppSettings.MIN_CHUNK, AppSettings.MAX_CHUNK),
    rpcPort = rpcPort.coerceIn(0, 65535),
    userAgent = userAgent.ifBlank { AppSettings.BAIDU_UA },
    httpUserAgent = httpUserAgent.ifBlank { AppSettings.DEFAULT_HTTP_USER_AGENT }
)
