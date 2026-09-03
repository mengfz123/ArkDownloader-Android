package com.ark.chunkdownloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ark.chunkdownloader.data.model.AppSettings
import com.ark.chunkdownloader.ui.components.ArkField
import com.ark.chunkdownloader.ui.components.ArkSliderRow
import com.ark.chunkdownloader.ui.components.PillButton
import com.ark.chunkdownloader.ui.components.PrimaryDockButton
import com.ark.chunkdownloader.ui.components.SurfacePanel
import com.ark.chunkdownloader.ui.theme.ArkBg
import com.ark.chunkdownloader.ui.theme.ArkBorder
import com.ark.chunkdownloader.ui.theme.ArkCard
import com.ark.chunkdownloader.ui.theme.ArkDimens
import com.ark.chunkdownloader.ui.theme.ArkError
import com.ark.chunkdownloader.ui.theme.ArkSidebar
import com.ark.chunkdownloader.ui.theme.ArkSuccess
import com.ark.chunkdownloader.ui.theme.ArkText
import com.ark.chunkdownloader.ui.viewmodel.MainViewModel
import com.ark.chunkdownloader.util.FilePublish
import com.ark.chunkdownloader.util.FormatUtil
import com.ark.chunkdownloader.util.UrlResolve

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTaskScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current
    var urls by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var dir by remember {
        mutableStateOf(
            settings.defaultSaveDir.ifBlank {
                FilePublish.defaultDownloadDir(context).absolutePath
            }
        )
    }
    var threads by remember { mutableFloatStateOf(settings.maxThreads.toFloat()) }
    var resolveMsg by remember { mutableStateOf("") }
    var resolveOk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = ArkBg,
        topBar = {
            TopAppBar(
                title = { Text("新建任务", color = ArkText) },
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
                    .padding(horizontal = ArkDimens.dockPadH, vertical = ArkDimens.dockPadV)
                    .navigationBarsPadding()
            ) {
                PrimaryDockButton(
                    label = if (busy) "提交中…" else "开始下载",
                    enabled = urls.isNotBlank() && !busy,
                    onClick = {
                        val list = MainViewModel.parseUrlPaste(urls)
                        if (list.isEmpty()) return@PrimaryDockButton
                        busy = true
                        vm.updateSettings { s -> s.copy(defaultSaveDir = dir.trim()) }
                        vm.createTasks(
                            list,
                            fileName.trim().ifBlank { null },
                            threads.toInt(),
                            settings.chunkSize,
                            "{}"
                        )
                        onBack()
                    }
                )
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(ArkDimens.pagePad)
        ) {
            SurfacePanel {
                ArkField(
                    label = "下载链接",
                    value = urls,
                    onChange = { urls = it },
                    singleLine = false,
                    minLines = 6,
                    placeholder = "每行一个 URL，支持百度直链 / HTTP",
                    required = true
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    PillButton("检测链接", onClick = {
                        val first = MainViewModel.parseUrlPaste(urls).firstOrNull()
                        if (first == null) {
                            resolveMsg = "请先填写链接"
                            resolveOk = false
                            return@PillButton
                        }
                        runCatching {
                            val r = UrlResolve.resolve(first, fileName.ifBlank { null })
                            resolveMsg = "${if (r.kind == UrlResolve.Kind.BAIDU) "百度" else "HTTP"} · ${r.name}" +
                                if (r.size > 0) " · ${FormatUtil.formatBytes(r.size)}" else ""
                            resolveOk = true
                            if (fileName.isBlank() && MainViewModel.parseUrlPaste(urls).size == 1) {
                                fileName = r.name
                            }
                        }.onFailure {
                            resolveMsg = it.message ?: it.toString()
                            resolveOk = false
                        }
                    })
                    Spacer(Modifier.weight(0.02f))
                    if (resolveMsg.isNotBlank()) {
                        Text(
                            resolveMsg,
                            color = if (resolveOk) ArkSuccess else ArkError,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp, top = 6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(ArkDimens.formCardGap))

            SurfacePanel {
                ArkField(
                    label = "文件名（可选，单任务生效）",
                    value = fileName,
                    onChange = { fileName = it },
                    placeholder = "留空自动识别"
                )
                ArkSliderRow(
                    title = "连接数",
                    valueLabel = "${threads.toInt()}",
                    value = threads,
                    onChange = { threads = it },
                    valueRange = 1f..AppSettings.MAX_THREADS.toFloat(),
                    steps = AppSettings.MAX_THREADS - 2
                )
                ArkField(
                    label = "保存目录（绝对路径）",
                    value = dir,
                    onChange = { dir = it },
                    singleLine = false,
                    minLines = 2
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
