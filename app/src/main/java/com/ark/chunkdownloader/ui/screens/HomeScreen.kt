package com.ark.chunkdownloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ark.chunkdownloader.data.model.AppSettings
import com.ark.chunkdownloader.data.model.DownloadTask
import com.ark.chunkdownloader.data.model.TaskStatus
import com.ark.chunkdownloader.ui.theme.ArkBorder
import com.ark.chunkdownloader.ui.theme.ArkCard
import com.ark.chunkdownloader.ui.theme.ArkError
import com.ark.chunkdownloader.ui.theme.ArkMuted
import com.ark.chunkdownloader.ui.theme.ArkPrimary
import com.ark.chunkdownloader.ui.theme.ArkSub
import com.ark.chunkdownloader.ui.theme.ArkSuccess
import com.ark.chunkdownloader.ui.theme.ArkWarn
import com.ark.chunkdownloader.ui.viewmodel.MainViewModel
import com.ark.chunkdownloader.util.FormatUtil

private enum class HomeTab { Downloading, Completed, Failed }

@Composable
fun HomeScreen(vm: MainViewModel, onCreate: () -> Unit, onSettings: () -> Unit) {
    val tasks by vm.tasks.collectAsState()
    val rpcOn by vm.rpcRunning.collectAsState()
    val settings by vm.settings.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        HomeTab.Downloading to "正在下载",
        HomeTab.Completed to "已完成",
        HomeTab.Failed to "失败"
    )

    val filtered = remember(tasks, tab) {
        when (tabs[tab].first) {
            HomeTab.Downloading -> tasks.filter {
                it.status == TaskStatus.DOWNLOADING ||
                    it.status == TaskStatus.MERGING ||
                    it.status == TaskStatus.PENDING ||
                    it.status == TaskStatus.PAUSED
            }
            HomeTab.Completed -> tasks.filter { it.status == TaskStatus.COMPLETED }
            HomeTab.Failed -> tasks.filter {
                it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELED
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate, containerColor = ArkPrimary) {
                Icon(Icons.Default.Add, contentDescription = "新建", tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        AppSettings.APP_NAME,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Android 原生 · v${AppSettings.VERSION}",
                        color = ArkSub,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            }

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArkCard).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .width(8.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (rpcOn) ArkSuccess else ArkMuted)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        !settings.rpcEnabled -> "RPC 已关闭"
                        rpcOn && settings.rpcRemote -> "RPC :${settings.rpcPort} · 局域网可访问"
                        rpcOn -> "RPC :${settings.rpcPort} · 仅本机"
                        else -> "RPC 未运行"
                    },
                    color = if (rpcOn) ArkSuccess else ArkSub,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = { vm.pauseAll() }) { Text("全部暂停", style = MaterialTheme.typography.labelMedium) }
                TextButton(onClick = { vm.resumeAll() }) { Text("全部继续", style = MaterialTheme.typography.labelMedium) }
                TextButton(onClick = { vm.clearCompleted() }) { Text("清空已完成", style = MaterialTheme.typography.labelMedium) }
            }

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEachIndexed { idx, (_, label) ->
                    Button(
                        onClick = { tab = idx },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (tab == idx) ArkPrimary.copy(alpha = 0.2f) else ArkCard,
                            contentColor = if (tab == idx) ArkPrimary else ArkSub
                        ),
                        modifier = Modifier.height(32.dp)
                    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无任务", color = ArkSub)
                        Text("点击右下角 + 创建下载", color = ArkMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(filtered, key = { it.id }) { task ->
                        TaskCard(task, vm)
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(task: DownloadTask, vm: MainViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ArkCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, ArkBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.fileName,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )
                Text(statusLabel(task.status), color = statusColor(task.status), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { task.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (task.status == TaskStatus.COMPLETED) ArkSuccess else ArkPrimary,
                trackColor = ArkBorder
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${FormatUtil.formatBytes(task.loaded)} / ${FormatUtil.formatBytes(task.totalSize)}",
                    color = ArkSub,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    if (task.speed > 0) FormatUtil.formatSpeed(task.speed) else "${task.progress.toInt()}%",
                    color = ArkPrimary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            task.errorMsg?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = ArkError, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (task.status) {
                    TaskStatus.DOWNLOADING, TaskStatus.MERGING, TaskStatus.PENDING ->
                        ActionBtn("暂停") { vm.pauseTask(task) }
                    TaskStatus.PAUSED, TaskStatus.FAILED ->
                        ActionBtn("继续") { vm.resumeTask(task) }
                    else -> {}
                }
                if (task.status == TaskStatus.FAILED) ActionBtn("重试") { vm.restartTask(task) }
                if (task.status != TaskStatus.COMPLETED) ActionBtn("取消") { vm.cancelTask(task) }
                ActionBtn("删除") { vm.deleteTask(task, true) }
            }
        }
    }
}

@Composable
private fun ActionBtn(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = ArkBorder, contentColor = ArkSub),
        modifier = Modifier.height(32.dp),
        contentPadding = ButtonDefaults.ContentPadding
    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
}

private fun statusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.PENDING -> "等待"
    TaskStatus.DOWNLOADING -> "下载中"
    TaskStatus.MERGING -> "收尾中"
    TaskStatus.PAUSED -> "已暂停"
    TaskStatus.COMPLETED -> "已完成"
    TaskStatus.FAILED -> "失败"
    TaskStatus.CANCELED -> "已取消"
}

private fun statusColor(status: TaskStatus) = when (status) {
    TaskStatus.COMPLETED -> ArkSuccess
    TaskStatus.FAILED -> ArkError
    TaskStatus.PAUSED -> ArkWarn
    TaskStatus.DOWNLOADING, TaskStatus.MERGING, TaskStatus.PENDING -> ArkPrimary
    TaskStatus.CANCELED -> ArkSub
}
