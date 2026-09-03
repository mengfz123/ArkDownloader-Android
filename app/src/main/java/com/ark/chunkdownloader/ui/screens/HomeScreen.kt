package com.ark.chunkdownloader.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ark.chunkdownloader.R
import com.ark.chunkdownloader.data.model.AppSettings
import com.ark.chunkdownloader.data.model.DownloadTask
import com.ark.chunkdownloader.data.model.TaskStatus
import com.ark.chunkdownloader.ui.components.HeaderPill
import com.ark.chunkdownloader.ui.components.PillButton
import com.ark.chunkdownloader.ui.components.PrimaryDockButton
import com.ark.chunkdownloader.ui.components.RpcDot
import com.ark.chunkdownloader.ui.components.StatusBadge
import com.ark.chunkdownloader.ui.components.TagChip
import com.ark.chunkdownloader.ui.theme.ArkBg
import com.ark.chunkdownloader.ui.theme.ArkBorder
import com.ark.chunkdownloader.ui.theme.ArkCard
import com.ark.chunkdownloader.ui.theme.ArkCard2
import com.ark.chunkdownloader.ui.theme.ArkDimens
import com.ark.chunkdownloader.ui.theme.ArkError
import com.ark.chunkdownloader.ui.theme.ArkErrorSoft
import com.ark.chunkdownloader.ui.theme.ArkMuted
import com.ark.chunkdownloader.ui.theme.ArkPrimary
import com.ark.chunkdownloader.ui.theme.ArkPrimarySoft
import com.ark.chunkdownloader.ui.theme.ArkSidebar
import com.ark.chunkdownloader.ui.theme.ArkSuccess
import com.ark.chunkdownloader.ui.theme.ArkSuccessSoft
import com.ark.chunkdownloader.ui.theme.ArkText
import com.ark.chunkdownloader.ui.theme.ArkWarn
import com.ark.chunkdownloader.ui.theme.ArkWarnSoft
import com.ark.chunkdownloader.ui.viewmodel.MainViewModel
import com.ark.chunkdownloader.util.FilePublish
import com.ark.chunkdownloader.util.FormatUtil
import com.ark.chunkdownloader.util.UrlResolve
import java.io.File

private enum class HomeTab { Downloading, Completed, Failed }

@Composable
fun HomeScreen(vm: MainViewModel, onCreate: () -> Unit, onSettings: () -> Unit) {
    val tasks by vm.tasks.collectAsState()
    val rpcOn by vm.rpcRunning.collectAsState()
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current

    var tab by remember { mutableIntStateOf(0) }
    var contactOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DownloadTask?>(null) }
    var deleteFiles by remember { mutableStateOf(false) }

    val tabs = listOf(
        HomeTab.Downloading to "正在下载",
        HomeTab.Completed to "已完成",
        HomeTab.Failed to "失败"
    )

    val counts = remember(tasks) {
        listOf(
            tasks.count {
                it.status in setOf(
                    TaskStatus.DOWNLOADING, TaskStatus.MERGING, TaskStatus.PENDING, TaskStatus.PAUSED
                )
            },
            tasks.count { it.status == TaskStatus.COMPLETED },
            tasks.count { it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELED }
        )
    }

    val filtered = remember(tasks, tab) {
        when (tabs[tab].first) {
            HomeTab.Downloading -> tasks.filter {
                it.status in setOf(
                    TaskStatus.DOWNLOADING, TaskStatus.MERGING, TaskStatus.PENDING, TaskStatus.PAUSED
                )
            }
            HomeTab.Completed -> tasks.filter { it.status == TaskStatus.COMPLETED }
            HomeTab.Failed -> tasks.filter {
                it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELED
            }
        }.sortedByDescending { it.createdAt }
    }

    val running = tasks.count { it.status == TaskStatus.DOWNLOADING }
    val pending = tasks.count { it.status == TaskStatus.PENDING }
    val totalSpeed = tasks.filter { it.status == TaskStatus.DOWNLOADING }.sumOf { it.speed }

    Scaffold(
        containerColor = ArkBg,
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(ArkCard)
                    .border(width = 0.dp, color = Color.Transparent)
            ) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(ArkBorder))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = ArkDimens.dockPadH, vertical = ArkDimens.dockPadV)
                ) {
                    PrimaryDockButton("＋ 新建任务", onClick = onCreate)
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(ArkSidebar)
                    .border(width = 0.dp, color = Color.Transparent)
                    .padding(horizontal = ArkDimens.headerPadH, vertical = ArkDimens.headerPadV),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_brand),
                    contentDescription = null,
                    modifier = Modifier
                        .size(ArkDimens.logo)
                        .clip(RoundedCornerShape(ArkDimens.logoRadius))
                )
                Spacer(Modifier.width(ArkDimens.brandGap))
                Column(Modifier.weight(1f)) {
                    Text(
                        AppSettings.APP_NAME,
                        color = ArkText,
                        fontSize = ArkDimens.appName,
                        fontWeight = FontWeight.Bold
                    )
                    Text("下载器", color = ArkMuted, fontSize = ArkDimens.appSub)
                }
                HeaderPill("联系我们") { contactOpen = true }
                Spacer(Modifier.width(ArkDimens.headerPillGap))
                HeaderPill("设置", onClick = onSettings)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(ArkBorder))

            // RPC bar — full width (edge to edge under header)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(ArkCard)
                    .border(width = 0.dp, color = Color.Transparent)
                    .clickable(onClick = onSettings)
                    .padding(horizontal = ArkDimens.rpcPadH, vertical = ArkDimens.rpcPadV),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RpcDot(rpcOn && settings.rpcEnabled)
                Spacer(Modifier.width(8.dp))
                Text("RPC", color = ArkMuted, fontSize = ArkDimens.rpcLabel)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        !settings.rpcEnabled -> "已关闭"
                        rpcOn && settings.rpcRemote -> "运行中 · 0.0.0.0:${settings.rpcPort}"
                        rpcOn -> "运行中 · 127.0.0.1:${settings.rpcPort}"
                        else -> "未运行"
                    },
                    color = ArkText,
                    fontSize = ArkDimens.rpcText,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text("›", color = ArkMuted, fontSize = ArkDimens.rpcText)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(ArkBorder))

            // Tabs
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(ArkSidebar)
                    .padding(horizontal = ArkDimens.segPadH, vertical = ArkDimens.segPadV),
                horizontalArrangement = Arrangement.spacedBy(ArkDimens.segGap)
            ) {
                tabs.forEachIndexed { idx, (_, label) ->
                    val on = tab == idx
                    Row(
                        Modifier
                            .weight(1f)
                            .height(ArkDimens.segHeight)
                            .clip(RoundedCornerShape(ArkDimens.segRadius))
                            .background(if (on) ArkPrimarySoft else Color.Transparent)
                            .clickable { tab = idx },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            label,
                            color = if (on) ArkPrimary else ArkMuted,
                            fontSize = ArkDimens.segText,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Spacer(Modifier.width(ArkDimens.segGap))
                        Box(
                            Modifier
                                .height(ArkDimens.segNumH)
                                .widthIn(min = ArkDimens.segNumMinW)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (on) Color(0x474A84FF) else ArkCard2)
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${counts[idx]}",
                                color = if (on) ArkPrimary else ArkMuted,
                                fontSize = ArkDimens.segNumText
                            )
                        }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(ArkBorder))

            // Stats
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ArkDimens.statPadH, vertical = ArkDimens.statPadV),
                horizontalArrangement = Arrangement.spacedBy(ArkDimens.statGap)
            ) {
                StatItem("总速度", FormatUtil.formatSpeed(totalSpeed))
                StatItem("进行中", "$running")
                StatItem("排队", "$pending")
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(ArkBorder))

            if (filtered.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "暂无${tabs[tab].second}任务",
                        color = ArkText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "点击底部按钮添加百度直链或 HTTP 下载",
                        color = ArkMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = ArkDimens.listPadH,
                        vertical = ArkDimens.listPadV
                    ),
                    verticalArrangement = Arrangement.spacedBy(ArkDimens.cardGap)
                ) {
                    items(filtered, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onPause = { vm.pauseTask(task) },
                            onResume = { vm.resumeTask(task) },
                            onRestart = { vm.restartTask(task) },
                            onCopy = {
                                copyText(context, task.url)
                                toast(context, "已复制")
                            },
                            onOpen = {
                                val ok = FilePublish.openFile(context, File(task.filePath))
                                if (!ok) toast(context, "无法打开文件")
                            },
                            onDelete = {
                                deleteFiles = false
                                deleteTarget = task
                            }
                        )
                    }
                    item { Spacer(Modifier.height(ArkDimens.listPadV)) }
                }
            }
        }
    }

    if (contactOpen) {
        AlertDialog(
            onDismissRequest = { contactOpen = false },
            containerColor = ArkCard,
            title = {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "联系我们",
                        color = ArkText,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            },
            text = {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "欢迎加入 QQ 交流群",
                        color = ArkMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(14.dp))
                    Image(
                        painter = painterResource(R.drawable.qq_group),
                        contentDescription = "QQ群",
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("加群密码", color = ArkMuted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ArkCard2)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                "arkDownloader",
                                color = ArkPrimary,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        PillButton("复制", primary = true, onClick = {
                            copyText(context, "arkDownloader")
                            toast(context, "密码已复制")
                        })
                    }
                }
            },
            confirmButton = {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { contactOpen = false }) {
                        Text("关闭", color = ArkPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = ArkCard,
            title = {
                Text("删除任务", color = ArkText, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ArkBg)
                            .border(1.dp, ArkBorder, RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            UrlResolve.displayFileName(target.fileName, target.url),
                            color = ArkText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "仅移除任务记录，或同时删除本地已下载文件",
                        color = ArkMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ArkBg)
                            .border(1.dp, ArkBorder, RoundedCornerShape(10.dp))
                            .clickable { deleteFiles = !deleteFiles }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = deleteFiles,
                            onCheckedChange = { deleteFiles = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = ArkPrimary,
                                uncheckedColor = ArkBorder
                            )
                        )
                        Text("同时删除已下载的文件", color = ArkText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTask(target, deleteFiles)
                    deleteTarget = null
                    toast(context, "已删除")
                }) {
                    Text("删除", color = ArkError)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消", color = ArkText)
                }
            }
        )
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ArkMuted, fontSize = ArkDimens.statText)
        Spacer(Modifier.width(4.dp))
        Text(value, color = ArkText, fontSize = ArkDimens.statText, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TaskCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val kind = if (UrlResolve.isBaiduUrl(task.url)) "百度直链" else "HTTP"
    val badgeColor = statusColor(task.status)
    val badgeSoft = statusSoft(task.status)
    val pct = task.progress.coerceIn(0f, 100f)
    val name = UrlResolve.displayFileName(task.fileName, task.url)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ArkDimens.cardRadius))
            .background(ArkCard)
            .border(1.dp, ArkBorder, RoundedCornerShape(ArkDimens.cardRadius))
            .padding(ArkDimens.cardPad)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                name,
                modifier = Modifier.weight(1f),
                color = ArkText,
                fontSize = ArkDimens.fileName,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            StatusBadge(statusLabel(task.status), badgeColor, badgeSoft)
        }
        Spacer(Modifier.height(ArkDimens.tagsTop))
        Row(
            horizontalArrangement = Arrangement.spacedBy(ArkDimens.tagGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TagChip(kind)
            TagChip(FormatUtil.formatBytes(task.totalSize), muted = true)
        }

        Spacer(Modifier.height(ArkDimens.progTop))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier
                    .weight(1f)
                    .height(ArkDimens.trackH)
                    .clip(RoundedCornerShape(2.dp)),
                color = when (task.status) {
                    TaskStatus.COMPLETED -> ArkSuccess
                    TaskStatus.FAILED, TaskStatus.CANCELED -> ArkError
                    else -> ArkPrimary
                },
                trackColor = ArkCard2
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (task.status == TaskStatus.DOWNLOADING) String.format("%.1f%%", pct)
                else String.format("%.0f%%", pct),
                color = ArkMuted,
                fontSize = ArkDimens.metaText,
                modifier = Modifier.width(ArkDimens.pctW)
            )
        }
        Spacer(Modifier.height(ArkDimens.metaTop))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${FormatUtil.formatBytes(task.loaded)} / ${FormatUtil.formatBytes(task.totalSize)}",
                color = ArkMuted,
                fontSize = ArkDimens.metaText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when {
                    task.status == TaskStatus.DOWNLOADING && task.speed > 0 -> {
                        val left = ((task.totalSize - task.loaded).coerceAtLeast(0)).toDouble() / task.speed
                        "${FormatUtil.formatSpeed(task.speed)} · ${FormatUtil.formatEta(left)}"
                    }
                    task.status == TaskStatus.MERGING -> "正在收尾…"
                    else -> ""
                },
                color = ArkMuted,
                fontSize = ArkDimens.metaText,
                maxLines = 1
            )
        }

        task.errorMsg?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(ArkDimens.metaTop))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ArkDimens.searchRadius))
                    .background(ArkErrorSoft)
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(it, color = ArkError, fontSize = ArkDimens.metaText, maxLines = 2)
            }
        }

        Spacer(Modifier.height(ArkDimens.actionTop))
        Box(Modifier.fillMaxWidth().height(1.dp).background(ArkBorder))
        Spacer(Modifier.height(ArkDimens.actionPadTop))
        Row(
            horizontalArrangement = Arrangement.spacedBy(ArkDimens.actionGap),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            when (task.status) {
                TaskStatus.DOWNLOADING, TaskStatus.MERGING ->
                    PillButton("暂停", onClick = onPause)
                TaskStatus.PAUSED, TaskStatus.PENDING ->
                    PillButton("继续", onClick = onResume, primary = true)
                TaskStatus.FAILED ->
                    PillButton("重试", onClick = onRestart, primary = true)
                TaskStatus.COMPLETED ->
                    PillButton("打开", onClick = onOpen, primary = true)
                else -> {}
            }
            PillButton("复制", onClick = onCopy, ghost = true)
            PillButton("删除", onClick = onDelete, danger = true)
        }
    }
}

private fun statusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.PENDING -> "等待"
    TaskStatus.DOWNLOADING -> "下载中"
    TaskStatus.MERGING -> "收尾"
    TaskStatus.PAUSED -> "暂停"
    TaskStatus.COMPLETED -> "完成"
    TaskStatus.FAILED -> "失败"
    TaskStatus.CANCELED -> "取消"
}

private fun statusColor(status: TaskStatus) = when (status) {
    TaskStatus.COMPLETED -> ArkSuccess
    TaskStatus.FAILED -> ArkError
    TaskStatus.PAUSED, TaskStatus.PENDING, TaskStatus.MERGING -> ArkWarn
    TaskStatus.DOWNLOADING -> ArkPrimary
    TaskStatus.CANCELED -> ArkMuted
}

private fun statusSoft(status: TaskStatus) = when (status) {
    TaskStatus.COMPLETED -> ArkSuccessSoft
    TaskStatus.FAILED -> ArkErrorSoft
    TaskStatus.PAUSED, TaskStatus.PENDING, TaskStatus.MERGING -> ArkWarnSoft
    TaskStatus.DOWNLOADING -> ArkPrimarySoft
    TaskStatus.CANCELED -> ArkCard2
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ark", text))
}

private fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
