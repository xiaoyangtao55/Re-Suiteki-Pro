package com.github.sky130.suiteki.pro.screen.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.sky130.suiteki.pro.logic.ble.AbstractSuitekiDevice
import com.github.sky130.suiteki.pro.logic.ble.AppInfo
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.logic.ble.WatchfaceInfo
import com.github.sky130.suiteki.pro.screen.main.home.DisconnectScreen
import com.github.sky130.suiteki.pro.util.AppNameStore
import com.github.sky130.suiteki.pro.util.InstallRecordStore
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Destination<RootGraph>
fun AppScreen(navigator: DestinationsNavigator) {
    val device by SuitekiManager.bleDevice.collectAsState()

    // 只有能拉表盘的设备（小米系）才出现第二个页签，华米保持原来的单页
    val tabs = remember(device) {
        if (device?.supportsWatchfaces == true) listOf("应用", "表盘") else listOf("应用")
    }
    var tab by remember(tabs) { mutableIntStateOf(0) }

    // 手动命名：点卡片弹出，存进 AppNameStore
    var editing by remember { mutableStateOf<AppInfo?>(null) }
    var editText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<WatchfaceInfo?>(null) }

    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = {
            if (tab == 1) device?.requestWatchfaceList() else device?.requestAppList()
        }) {
            Icon(Icons.Default.Refresh, contentDescription = null)
        }
    }, topBar = {
        TopAppBar(title = { Text(if (tab == 1) "表盘管理" else "应用管理") }, navigationIcon = {
            IconButton(onClick = { navigator.popBackStack() }) {
                Icon(Icons.AutoMirrored.Default.ArrowBack, null)
            }
        })
    }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 10.dp)
        ) {
            if (device == null) {
                DisconnectScreen { navigator.popBackStack() }
            } else {
                val current = device!!
                LaunchedEffect(current) {
                    current.requestAppList()
                    current.requestWatchfaceList()
                }

                if (tabs.size > 1) {
                    TabRow(selectedTabIndex = tab) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = tab == index,
                                onClick = { tab = index },
                                text = { Text(title) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (tab == 1) {
                    WatchfaceList(current) { deleting = it }
                } else {
                    AppList(current) { app ->
                        editing = app
                        editText = AppNameStore.get(app.id).orEmpty()
                    }
                }
            }
        }
    }

    editing?.let { app ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("给这个应用起个名字") },
            text = {
                Column {
                    Text(app.id, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        label = { Text("应用名，留空则恢复默认") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AppNameStore.put(app.id, editText)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            },
        )
    }

    deleting?.let { watchface ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除表盘") },
            text = { Text("将从手环上删除「${watchface.name}」，删掉后需要重新安装才能恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    device?.deleteWatchface(watchface.id)
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AppList(device: AbstractSuitekiDevice, onRename: (AppInfo) -> Unit) {
    if (device.appList.isEmpty()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("还没拿到应用列表")
            Text(
                "列表要等设备返回。点右下角刷新重试；如果一直为空，可能是这台机型不支持列表查询。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(device.appList) { app ->
            // 手动命名优先；没起过名字就用安装时记下的（表盘是文件名，小程序是 app.json）
            val record = InstallRecordStore.get(app.id)
            val label = AppNameStore.get(app.id) ?: record?.name?.takeIf { it.isNotBlank() }
            ElevatedCard(
                onClick = { onRename(app) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .padding(10.dp)
                        .fillMaxWidth()
                ) {
                    Column {
                        Text(label ?: app.id)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            buildString {
                                // 华米的表盘也混在 0x00A0 列表里，用安装时记下的类型区分
                                record?.kindLabel?.let { append(it); append("    ") }
                                append(if (app.name.isBlank()) "版本未知" else "版本 ${app.name}")
                                if (label != null) {
                                    append("    ")
                                    append(app.id)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Row(
                        modifier = Modifier.align(
                            Alignment.CenterEnd
                        ), horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        IconButton(
                            onClick = { device.deleteApp(app.id) },
                        ) {
                            Icon(Icons.Default.Delete, null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchfaceList(device: AbstractSuitekiDevice, onDelete: (WatchfaceInfo) -> Unit) {
    val list = device.watchfaceList
    if (list.isEmpty()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("还没拿到表盘列表")
            Text(
                "表盘要等认证完成后才会返回。如果一直为空，可能是这台机型不支持表盘管理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list) { wf ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .padding(10.dp)
                        .fillMaxWidth()
                ) {
                    Column {
                        Text(wf.name)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            buildString {
                                if (wf.active) append("当前使用    ")
                                if (!wf.canDelete) append("内置    ")
                                append(wf.id)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        // 内置表盘和正在用的表盘设备不让删，这里直接禁掉，
                        // 不要等用户点了才弹「不能删除」
                        IconButton(
                            onClick = { onDelete(wf) },
                            enabled = wf.canDelete && !wf.active,
                        ) {
                            Icon(Icons.Default.Delete, null)
                        }
                    }
                }
            }
        }
    }
}
