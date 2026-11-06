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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.sky130.suiteki.pro.logic.ble.AppInfo
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.screen.main.home.DisconnectScreen
import com.github.sky130.suiteki.pro.util.AppNameStore
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Destination<RootGraph>
fun AppScreen(navigator: DestinationsNavigator) {
    val device by SuitekiManager.bleDevice.collectAsState()

    // 手动命名：点卡片弹出，存进 AppNameStore
    var editing by remember { mutableStateOf<AppInfo?>(null) }
    var editText by remember { mutableStateOf("") }

    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = { device?.requestAppList() }) {
            Icon(Icons.Default.Refresh, contentDescription = null)
        }
    }, topBar = {
        TopAppBar(title = { Text("应用管理") }, navigationIcon = {
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
                DisconnectScreen()
            } else {
                // 0x00a0 是明文通道，连上就能拉，不用等认证
                LaunchedEffect(device) { device?.requestAppList() }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(device!!.appList) { app ->
                        val label = AppNameStore.get(app.id)
                        ElevatedCard(
                            onClick = {
                                editing = app
                                editText = label.orEmpty()
                            },
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
                                        onClick = { device?.deleteApp(app.id) },
                                    ) {
                                        Icon(Icons.Default.Delete, null)
                                    }
                                }
                            }
                        }
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
}