package com.ark.chunkdownloader.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ark.chunkdownloader.data.model.AppSettings
import com.ark.chunkdownloader.ui.theme.ArkBorder
import com.ark.chunkdownloader.ui.theme.ArkCard
import com.ark.chunkdownloader.ui.theme.ArkPrimary
import com.ark.chunkdownloader.ui.theme.ArkSub
import com.ark.chunkdownloader.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val connections = remember(settings.maxThreads) {
        mutableFloatStateOf(settings.maxThreads.toFloat())
    }
    val maxRunning = remember(settings.maxConcurrentTasks) {
        mutableFloatStateOf(settings.maxConcurrentTasks.toFloat())
    }
    val chunkMb = remember(settings.chunkSize) {
        mutableFloatStateOf(settings.chunkSizeMb.toFloat())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全局设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ArkCard)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("下载参数", color = ArkPrimary, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            Field(
                "下载目录（downloadDir）",
                settings.defaultSaveDir,
                { vm.updateSettings { s -> s.copy(defaultSaveDir = it) } },
                placeholder = "留空则使用 Download/ArkDownloads"
            )
            Spacer(Modifier.height(12.dp))

            Text(
                "连接数（connections）: ${connections.floatValue.toInt()}（1–${AppSettings.MAX_THREADS}，默认 ${AppSettings.DEFAULT_CONNECTIONS}）",
                color = ArkSub
            )
            Slider(
                value = connections.floatValue,
                onValueChange = {
                    connections.floatValue = it
                    vm.updateSettings { s -> s.copy(maxThreads = it.toInt()) }
                },
                valueRange = 1f..AppSettings.MAX_THREADS.toFloat(),
                steps = AppSettings.MAX_THREADS - 2
            )

            Text(
                "最大同时下载（maxRunning）: ${maxRunning.floatValue.toInt()}（1–${AppSettings.MAX_RUNNING}，默认 ${AppSettings.DEFAULT_MAX_RUNNING}）",
                color = ArkSub
            )
            Slider(
                value = maxRunning.floatValue,
                onValueChange = {
                    maxRunning.floatValue = it
                    vm.updateSettings { s -> s.copy(maxConcurrentTasks = it.toInt()) }
                },
                valueRange = 1f..AppSettings.MAX_RUNNING.toFloat(),
                steps = AppSettings.MAX_RUNNING - 2
            )

            Text(
                "分片大小（chunkSizeMb）: ${chunkMb.floatValue.toInt()} MB（${AppSettings.MIN_CHUNK_MB}–${AppSettings.MAX_CHUNK_MB}，默认 1）",
                color = ArkSub
            )
            Slider(
                value = chunkMb.floatValue,
                onValueChange = {
                    chunkMb.floatValue = it
                    vm.updateSettings { s ->
                        s.copy(chunkSize = AppSettings.chunkBytesFromMb(it.toInt()))
                    }
                },
                valueRange = AppSettings.MIN_CHUNK_MB.toFloat()..AppSettings.MAX_CHUNK_MB.toFloat(),
                steps = AppSettings.MAX_CHUNK_MB - AppSettings.MIN_CHUNK_MB - 1
            )

            SwitchRow("自动开始下载（autoStart）", settings.autoStart) {
                vm.updateSettings { s -> s.copy(autoStart = it) }
            }
            SwitchRow("完成时通知（notifyOnComplete）", settings.notifyOnComplete) {
                vm.updateSettings { s -> s.copy(notifyOnComplete = it) }
            }

            Spacer(Modifier.height(16.dp))
            Text("网络", color = ArkPrimary, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Field(
                "百度 User-Agent（userAgent）",
                settings.userAgent,
                { vm.updateSettings { s -> s.copy(userAgent = it) } },
                minLines = 2
            )
            Spacer(Modifier.height(8.dp))
            Field(
                "HTTP User-Agent（httpUserAgent）",
                settings.httpUserAgent,
                { vm.updateSettings { s -> s.copy(httpUserAgent = it) } }
            )

            Spacer(Modifier.height(16.dp))
            Text("RPC 服务", color = ArkPrimary, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            SwitchRow("启用 RPC（rpcEnabled）", settings.rpcEnabled) {
                vm.updateSettings { s -> s.copy(rpcEnabled = it) }
            }
            SwitchRow("允许局域网访问（rpcRemote，绑定 0.0.0.0）", settings.rpcRemote) {
                vm.updateSettings { s -> s.copy(rpcRemote = it) }
            }
            Field(
                "RPC 端口（rpcPort，默认 ${AppSettings.DEFAULT_RPC_PORT}）",
                settings.rpcPort.toString(),
                { v ->
                    v.toIntOrNull()?.let { p ->
                        vm.updateSettings { s -> s.copy(rpcPort = p) }
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            Field(
                "RPC 令牌（rpcToken，留空则不鉴权）",
                settings.rpcToken,
                { vm.updateSettings { s -> s.copy(rpcToken = it) } }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "接口: GET /health · GET /api/v1/info · GET/PUT /api/v1/config · " +
                    "GET/POST /api/v1/tasks · POST /api/v1/tasks/batch · " +
                    "PUT /api/v1/tasks/:id/pause|continue · POST /api/v1/resolve",
                color = ArkSub,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "${AppSettings.APP_NAME} Android  v${AppSettings.VERSION}",
                color = ArkSub,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    minLines: Int = 1,
    placeholder: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
        minLines = minLines,
        shape = RoundedCornerShape(10.dp),
        colors = fieldColors()
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ArkPrimary,
    unfocusedBorderColor = ArkBorder,
    focusedContainerColor = ArkCard,
    unfocusedContainerColor = ArkCard
)
