package com.ark.chunkdownloader.ui.screens

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import com.ark.chunkdownloader.data.model.AppSettings
import com.ark.chunkdownloader.ui.theme.ArkBorder
import com.ark.chunkdownloader.ui.theme.ArkCard
import com.ark.chunkdownloader.ui.theme.ArkPrimary
import com.ark.chunkdownloader.ui.theme.ArkSub
import com.ark.chunkdownloader.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTaskScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    var urls by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var headers by remember { mutableStateOf("{}") }
    var threads by remember { mutableFloatStateOf(settings.maxThreads.toFloat()) }
    var chunkMb by remember {
        mutableFloatStateOf(
            (settings.chunkSize / (1024 * 1024)).coerceIn(
                AppSettings.MIN_CHUNK_MB,
                AppSettings.MAX_CHUNK_MB
            ).toFloat()
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新建下载任务") },
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
            Field(
                "下载地址 *（支持多行粘贴，每行一个 URL）",
                urls,
                { urls = it },
                singleLine = false,
                minLines = 5
            )
            Spacer(Modifier.height(12.dp))
            Field("文件名（可选，仅单链接时生效）", fileName, { fileName = it })
            Spacer(Modifier.height(12.dp))
            Field("请求头 JSON（可选）", headers, { headers = it }, singleLine = false, minLines = 3)
            Spacer(Modifier.height(16.dp))
            Text("连接数: ${threads.toInt()}", color = ArkSub)
            Slider(
                value = threads,
                onValueChange = { threads = it },
                valueRange = 1f..AppSettings.MAX_THREADS.toFloat(),
                steps = AppSettings.MAX_THREADS - 2
            )
            Text("分片大小: ${chunkMb.toInt()} MB", color = ArkSub)
            Slider(
                value = chunkMb,
                onValueChange = { chunkMb = it },
                valueRange = AppSettings.MIN_CHUNK_MB.toFloat()..AppSettings.MAX_CHUNK_MB.toFloat(),
                steps = AppSettings.MAX_CHUNK_MB - AppSettings.MIN_CHUNK_MB - 1
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val list = MainViewModel.parseUrlPaste(urls)
                    if (list.isEmpty()) return@Button
                    val chunkSize = AppSettings.chunkBytesFromMb(chunkMb.toInt())
                    vm.createTasks(
                        list,
                        fileName.trim().ifBlank { null },
                        threads.toInt(),
                        chunkSize,
                        headers
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ArkPrimary),
                enabled = urls.isNotBlank()
            ) { Text("开始下载") }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = true,
    minLines: Int = 1
) {
    Text(label, color = ArkSub, style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ArkPrimary,
            unfocusedBorderColor = ArkBorder,
            focusedContainerColor = ArkCard,
            unfocusedContainerColor = ArkCard
        )
    )
}
