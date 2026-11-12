package com.github.sky130.suiteki.pro.screen.folder

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.sky130.suiteki.pro.MainApplication.Companion.toast
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.result.ResultBackNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 选安装包。
 *
 * 原来是自己从 /sdcard 一路 listFiles 浏览，这要求 MANAGE_EXTERNAL_STORAGE /
 * READ_EXTERNAL_STORAGE；现在改成系统文件选择器（SAF），选中后复制到 cache 再回传，
 * 应用不再需要任何存储权限，也不会去扫用户的相册和文档目录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Destination<RootGraph>
fun FolderScreen(
    resultNavigator: ResultBackNavigator<File>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    // 上一次导入的副本没用了，进页面清一次，避免 cache 越积越大
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { importDir(context).listFiles()?.forEach { it.delete() } }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val file = withContext(Dispatchers.IO) { copyToCache(context, uri) }
            busy = false
            if (file == null) {
                "读取文件失败，换一个试试".toast()
            } else {
                resultNavigator.navigateBack(result = file)
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(text = "选择文件") }, navigationIcon = {
            IconButton(onClick = { resultNavigator.navigateBack() }) {
                Icon(Icons.AutoMirrored.Default.ArrowBack, null)
            }
        })
    }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "选择要安装的文件",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "支持表盘 .bin、小程序 .rpk / .zab、固件包。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
            ) {
                Text(if (busy) "正在读取…" else "打开文件管理器")
            }
            Text(
                "只读取你在这里选中的那一个文件，不会浏览或扫描存储里的其它内容。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

private fun importDir(context: Context): File = File(context.cacheDir, "import").apply { mkdirs() }

/** 把选中的文档复制进缓存，下游安装逻辑仍然按 File 处理 */
private fun copyToCache(context: Context, uri: Uri): File? = runCatching {
    val name = displayName(context, uri).ifBlank { "install.bin" }
    val out = File(importDir(context), name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { input.copyTo(it) }
    } ?: return null
    out.takeIf { it.isFile && it.length() > 0 }
}.onFailure {
    Log.w("FolderScreen", "复制 $uri 失败", it)
}.getOrNull()

private fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0).orEmpty() else ""
    }.orEmpty()
}.getOrDefault("")
