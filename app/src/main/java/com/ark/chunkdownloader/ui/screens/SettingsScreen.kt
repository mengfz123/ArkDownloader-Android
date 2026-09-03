package com.ark.chunkdownloader.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ark.chunkdownloader.data.model.AppSettings
import com.ark.chunkdownloader.ui.components.ArkField
import com.ark.chunkdownloader.ui.components.ArkSliderRow
import com.ark.chunkdownloader.ui.components.ArkSwitchRow
import com.ark.chunkdownloader.ui.components.PrimaryDockButton
import com.ark.chunkdownloader.ui.components.SectionTitle
import com.ark.chunkdownloader.ui.components.SurfacePanel
import com.ark.chunkdownloader.ui.theme.ArkBg
import com.ark.chunkdownloader.ui.theme.ArkBorder
import com.ark.chunkdownloader.ui.theme.ArkCard
import com.ark.chunkdownloader.ui.theme.ArkMuted
import com.ark.chunkdownloader.ui.theme.ArkSidebar
import com.ark.chunkdownloader.ui.theme.ArkText
import com.ark.chunkdownloader.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current

    var downloadDir by remember(settings.defaultSaveDir) { mutableStateOf(settings.defaultSaveDir) }
    var connections by remember(settings.maxThreads) { mutableFloatStateOf(settings.maxThreads.toFloat()) }
    var chunkMb by remember(settings.chunkSize) { mutableFloatStateOf(settings.chunkSizeMb.toFloat()) }
    var maxRunning by remember(settings.maxConcurrentTasks) {
        mutableFloatStateOf(settings.maxConcurrentTasks.toFloat())
    }
    var autoStart by remember(settings.autoStart) { mutableStateOf(settings.autoStart) }
    var notifyOnComplete by remember(settings.notifyOnComplete) { mutableStateOf(settings.notifyOnComplete) }
    var userAgent by remember(settings.userAgent) { mutableStateOf(settings.userAgent) }
    var httpUserAgent by remember(settings.httpUserAgent) { mutableStateOf(settings.httpUserAgent) }
    var rpcEnabled by remember(settings.rpcEnabled) { mutableStateOf(settings.rpcEnabled) }
    var rpcRemote by remember(settings.rpcRemote) { mutableStateOf(settings.rpcRemote) }
    var rpcPort by remember(settings.rpcPort) { mutableStateOf(settings.rpcPort.toString()) }
    var rpcToken by remember(settings.rpcToken) { mutableStateOf(settings.rpcToken) }

    fun save() {
        val port = rpcPort.toIntOrNull() ?: AppSettings.DEFAULT_RPC_PORT
        vm.updateSettings { s ->
            s.copy(
                defaultSaveDir = downloadDir.trim(),
                maxThreads = connections.toInt(),
                chunkSize = AppSettings.chunkBytesFromMb(chunkMb.toInt()),
                maxConcurrentTasks = maxRunning.toInt(),
                autoStart = autoStart,
                notifyOnComplete = notifyOnComplete,
                userAgent = userAgent.trim().ifBlank { AppSettings.BAIDU_UA },
                httpUserAgent = httpUserAgent.trim().ifBlank { AppSettings.DEFAULT_HTTP_USER_AGENT },
                rpcEnabled = rpcEnabled,
                rpcRemote = rpcRemote,
                rpcPort = port,
                rpcToken = rpcToken.trim()
            )
        }
        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        containerColor = ArkBg,
        topBar = {
            TopAppBar(
                title = { Text("设置", color = ArkText) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = ArkText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ArkSidebar,
                    titleContentColor = ArkText
                )
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(ArkCard)
                    .border(width = 0.dp, color = ArkBorder)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .navigationBarsPadding()
            ) {
                PrimaryDockButton("保存设置", onClick = { save() })
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            SurfacePanel {
                SectionTitle("下载")
                ArkField(
                    label = "默认保存目录",
                    value = downloadDir,
                    onChange = { downloadDir = it },
                    singleLine = false,
                    minLines = 2,
                    placeholder = "绝对路径，留空使用 Download/ArkDownloads"
                )
                Text(
                    "绝对路径",
                    color = ArkMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
                ArkSliderRow(
                    title = "默认连接数（1–${AppSettings.MAX_THREADS}）",
                    valueLabel = "${connections.toInt()}",
                    value = connections,
                    onChange = { connections = it },
                    valueRange = 1f..AppSettings.MAX_THREADS.toFloat(),
                    steps = AppSettings.MAX_THREADS - 2
                )
                ArkSliderRow(
                    title = "分片大小（1–5 MB，默认 1）",
                    valueLabel = "${chunkMb.toInt()}M",
                    value = chunkMb,
                    onChange = { chunkMb = it },
                    valueRange = AppSettings.MIN_CHUNK_MB.toFloat()..AppSettings.MAX_CHUNK_MB.toFloat(),
                    steps = AppSettings.MAX_CHUNK_MB - AppSettings.MIN_CHUNK_MB - 1
                )
                Text(
                    "大分片会自动降低并发以免吞吐抖动；百度单片≤5MB。均对新建任务生效",
                    color = ArkMuted,
                    style = MaterialTheme.typography.labelSmall
                )
                ArkSliderRow(
                    title = "最大同时下载（1–${AppSettings.MAX_RUNNING}）",
                    valueLabel = "${maxRunning.toInt()}",
                    value = maxRunning,
                    onChange = { maxRunning = it },
                    valueRange = 1f..AppSettings.MAX_RUNNING.toFloat(),
                    steps = AppSettings.MAX_RUNNING - 2
                )
                ArkSwitchRow("添加后自动开始", autoStart) { autoStart = it }
                ArkSwitchRow("下载完成时通知", notifyOnComplete) { notifyOnComplete = it }
            }

            Spacer(Modifier.height(12.dp))

            SurfacePanel {
                SectionTitle("User-Agent")
                ArkField(
                    label = "百度直链 User-Agent",
                    value = userAgent,
                    onChange = { userAgent = it },
                    singleLine = false,
                    minLines = 2
                )
                ArkField(
                    label = "普通 HTTP User-Agent",
                    value = httpUserAgent,
                    onChange = { httpUserAgent = it }
                )
            }

            Spacer(Modifier.height(12.dp))

            SurfacePanel {
                SectionTitle("RPC 远程服务")
                ArkSwitchRow("启用 RPC 服务", rpcEnabled) { rpcEnabled = it }
                ArkSwitchRow("允许局域网/远程访问", rpcRemote) { rpcRemote = it }
                ArkField(
                    label = "RPC 端口（默认 ${AppSettings.DEFAULT_RPC_PORT}）",
                    value = rpcPort,
                    onChange = { rpcPort = it }
                )
                ArkField(
                    label = "RPC 访问令牌",
                    value = rpcToken,
                    onChange = { rpcToken = it },
                    placeholder = "留空不启用"
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "版本 ${AppSettings.VERSION} · 配置保存在本机",
                color = ArkMuted,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
