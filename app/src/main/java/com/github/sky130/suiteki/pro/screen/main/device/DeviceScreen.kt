package com.github.sky130.suiteki.pro.screen.main.device

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.clj.fastble.BleManager
import com.clj.fastble.callback.BleScanCallback
import com.clj.fastble.data.BleDevice
import com.clj.fastble.scan.BleScanRuleConfig
import com.github.sky130.suiteki.pro.MainApplication.Companion.context
import com.github.sky130.suiteki.pro.MainApplication.Companion.toast
import com.github.sky130.suiteki.pro.logic.ble.DeviceDriver
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.logic.database.AppDatabase
import com.github.sky130.suiteki.pro.logic.database.model.Device
import com.github.sky130.suiteki.pro.screen.main.MainGraph
import com.github.sky130.suiteki.pro.ui.widget.BaseSuitekiDialog
import com.github.sky130.suiteki.pro.ui.widget.DialogState
import com.github.sky130.suiteki.pro.ui.widget.SuitekiScaffold
import com.github.sky130.suiteki.pro.ui.widget.SuitekiDialog
import com.github.sky130.suiteki.pro.ui.widget.SuitekiTopBar
import com.github.sky130.suiteki.pro.ui.widget.rememberDialogState
import com.github.sky130.suiteki.pro.util.MiLogParser
import com.ramcosta.composedestinations.annotation.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Destination<MainGraph>
fun DeviceScreen() {
    val scope = rememberCoroutineScope()
    val addDialogState = rememberDialogState()
    val scanDialogState = rememberDialogState()

    SuitekiScaffold(fab = {
        FloatingActionButton(onClick = { addDialogState.show() }) {
            Icon(Icons.Filled.Add, null)
        }
    }, topBar = {
        SuitekiTopBar(title = "设备管理")
    }) {
        ScanLogDialog(state = scanDialogState)
        DeviceAddDialog(addDialogState, scanDialogState)
        Column {
            val list by AppDatabase.instance.device().getList().collectAsState(initial = listOf())
            // 连接动作统一放在主页，这里只做增删改，避免两处都能连、状态各自为政
            Text(
                "这里是设备的增删改。连接请在主页的「未连接设备」列表里点选。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(horizontal = 10.dp),
            ) {
                items(list) {
                    val editDialogState = rememberDialogState()
                    DeviceEditDialog(editDialogState, device = it)
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(10.dp)
                                .fillMaxWidth()
                        ) {
                            Column {
                                Text(it.name)
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    SuitekiManager.driver(it.driverId)?.displayName
                                        ?: "未知型号，请编辑后重新选择",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(it.mac)
                            }
                            Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                                IconButton(onClick = { editDialogState.show() }) {
                                    Icon(Icons.Default.Edit, null)
                                }
                                Spacer(Modifier.width(5.dp))
                                IconButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        AppDatabase.instance.device().delete(it)
                                    }
                                }) {
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

@Composable
fun ScanLogDialog(state: DialogState) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    // 必须在进入 RESUMED 之前注册，所以放在 visible 判断之前
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch(Dispatchers.IO) {
            val texts = readLogFiles(context.contentResolver, uris)
            val result = importLogs(texts)
            withContext(Dispatchers.Main) {
                busy = false
                result.toast()
            }
        }
    }

    if (!state.visible.value) return

    BaseSuitekiDialog(state = state, onDismissRequest = {
        state.dismiss()
    }, confirmButton = {
        TextButton(
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = !busy,
        ) {
            Text(text = if (busy) "解析中…" else "选择文件")
        }

    }, dismissButton = {
        TextButton(onClick = { state.dismiss() }) {
            Text(text = "退出")
        }
    }, icon = { Icon(imageVector = Icons.Default.Watch, contentDescription = null) }, title = {
        Text(
            text = "通过小米运动健康添加"
        )
    }, text = {

        val about = remember {
            """
            1. 将小米运动健康结束后台运行（不了解"后台运行"是什么的话，重启手机最省事）
            2. 进入小米运动健康，点右下角[我的]，滑到底部点[关于]
            3. 连续点击那个[橙色圆圈]（小米应用图标）
            4. 提示对话框出来时点确认，然后回到 Re:Suiteki Pro
            5. 点下面的[选择文件]，在系统选择器里选 Downloads/wearablelog 下刚生成的 log.zip
               （旧版导出的是 Wearable.log，也可以直接选）
            本应用不申请存储权限，只读取你在这里选中的文件。
            """.trimIndent()
        }

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (i in about.split("\n")) {
                Text(text = i)
            }
        }
    })
}

/** 按显示名 / 文件头判断是不是 zip，是就逐个 entry 取 .log，否则当纯文本读 */
private fun readLogFiles(resolver: ContentResolver, uris: List<Uri>): List<String> {
    val out = mutableListOf<String>()
    for (uri in uris) {
        runCatching {
            val name = displayName(resolver, uri)
            if (name.endsWith(".zip", true) || looksLikeZip(resolver, uri)) {
                resolver.openInputStream(uri)?.use { raw ->
                    ZipInputStream(BufferedInputStream(raw)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.endsWith(".log", true)) {
                                out += zis.readBytes().toString(Charsets.UTF_8)
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
            } else {
                resolver.openInputStream(uri)?.use { out += it.readBytes().toString(Charsets.UTF_8) }
            }
        }.onFailure { Log.w("ScanLog", "读取 $uri 失败", it) }
    }
    return out
}

private fun displayName(resolver: ContentResolver, uri: Uri): String =
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("")

private fun looksLikeZip(resolver: ContentResolver, uri: Uri): Boolean =
    runCatching {
        resolver.openInputStream(uri)?.use { s ->
            val head = ByteArray(2)
            s.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        } ?: false
    }.getOrDefault(false)

/**
 * 把日志正文落成设备记录。已有设备只更新密钥，不再插重复行。
 * 返回给用户看的提示文案。
 */
private suspend fun importLogs(texts: List<String>): String {
    if (texts.isEmpty()) {
        return "没有读到内容：请确认选中的是小米运动健康导出的 log.zip 或 Wearable.log"
    }

    val merged = LinkedHashMap<String, MiLogParser.Entry>()
    for (text in texts) {
        for (e in MiLogParser.parse(text)) {
            val id = e.mac.uppercase()
            val old = merged[id]
            if (old == null) merged[id] = e
            else if (old.key.isBlank() && e.key.isNotBlank()) merged[id] = old.copy(key = e.key)
        }
    }
    val deviceList = merged.values.map { Device(it.name, it.mac, it.key, it.driverId) }

    val existing = AppDatabase.instance.device().getAll()
    val toInsert = arrayListOf<Device>()
    var updated = 0
    for (d in deviceList) {
        val hit = existing.firstOrNull { it.mac.equals(d.mac, ignoreCase = true) }
        if (hit == null) {
            toInsert += d
        } else if (d.key.isNotBlank() && hit.key != d.key) {
            AppDatabase.instance.device().updateKey(hit.mac, d.key)
            updated++
        }
    }
    if (toInsert.isNotEmpty()) AppDatabase.instance.device().insert(toInsert)

    val withKey = deviceList.count { it.key.isNotBlank() }
    return when {
        deviceList.isEmpty() ->
            "日志里没有设备记录，请确认小米运动健康里绑定过这台手环"
        withKey == 0 ->
            "找到 ${deviceList.size} 台设备，但日志里没有密钥，无法用于认证"
        else -> buildString {
            append("扫描完成：${deviceList.size} 台，带密钥 $withKey 台")
            if (toInsert.isNotEmpty()) append("，新增 ${toInsert.size} 台")
            if (updated > 0) append("，更新密钥 $updated 台")
        }
    }
}

/** Android 11 及以下扫描 BLE 需要定位服务处于开启状态，12+ 不再需要 */
private fun isLocationServiceOn(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
    return runCatching {
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(true)
}

private val MAC_PATTERN = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

/**
 * 扫描附近的 BLE 设备。点一条只负责"回填 MAC + 推荐型号"，不直接连接——
 * 自动连等于又把判断权交回给广播名。
 */
@Composable
fun DeviceScanDialog(
    state: DialogState,
    onPick: (mac: String, bluetoothName: String?) -> Unit,
) {
    if (!state.visible.value) return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val devices = remember { mutableStateListOf<BleDevice>() }
    var scanning by remember { mutableStateOf(true) }
    // Android 11 及以下：光有定位权限不够，系统定位开关关着照样扫不到
    val locationMissing = remember { !isLocationServiceOn(context) }

    DisposableEffect(Unit) {
        val manager = BleManager.getInstance()
        manager.initScanRule(BleScanRuleConfig.Builder().setScanTimeOut(15_000).build())
        val callback = object : BleScanCallback() {
            private fun offer(device: BleDevice?) {
                device ?: return
                scope.launch {
                    if (devices.none { it.mac == device.mac }) devices.add(device)
                }
            }

            override fun onScanStarted(success: Boolean) {
                scope.launch { scanning = true }
            }

            override fun onScanning(device: BleDevice) = offer(device)

            override fun onLeScan(device: BleDevice) = offer(device)

            override fun onScanFinished(scanResultList: List<BleDevice>) {
                scope.launch { scanning = false }
            }
        }
        manager.scan(callback)
        onDispose { runCatching { manager.cancelScan() } }
    }

    SuitekiDialog(
        state = state,
        onDismissRequest = { state.dismiss() },
        button = {
            TextButton(onClick = { state.dismiss() }) { Text("关闭") }
        },
        icon = Icons.Filled.Watch,
        title = if (scanning) "正在扫描…" else "附近的设备",
    ) {
        if (devices.isEmpty()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    if (scanning) "让手环靠近手机并保持屏幕点亮" else "没扫到设备，确认蓝牙已开启后重试"
                )
                if (locationMissing) {
                    Text(
                        "系统定位开关没有打开。Android 11 及以下要求扫描蓝牙时必须开启定位，否则一个设备都扫不到。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    }) { Text("去打开定位") }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.height(320.dp)) {
                items(devices) { d ->
                    Card(
                        onClick = {
                            onPick(d.mac, d.name)
                            state.dismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(d.name?.takeIf { it.isNotBlank() } ?: "未知设备")
                                Text(d.mac, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("${d.rssi} dBm")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceAddDialog(dialogState: DialogState, scanDialogState: DialogState) {
    val scope = rememberCoroutineScope()
    val bleScanState = rememberDialogState()
    var name by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var driver by remember { mutableStateOf<DeviceDriver?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    // 备注名是否被用户手改过。没改过就跟随型号，改过就不再覆盖
    var nameEdited by remember { mutableStateOf(false) }

    fun applyDriver(d: DeviceDriver) {
        driver = d
        if (!nameEdited) name = d.displayName
    }

    // 扫到设备只做"回填 MAC + 预选型号"，最终仍由用户点确定
    DeviceScanDialog(bleScanState) { scannedMac, bluetoothName ->
        mac = scannedMac
        key = ""
        SuitekiManager.recommend(bluetoothName)?.let { applyDriver(it) }
    }

    SuitekiDialog(
        state = dialogState,
        onDismissRequest = { dialogState.dismiss() },
        button = {
            TextButton(onClick = {
                val d = driver
                if (!MAC_PATTERN.matches(mac.trim())) {
                    "MAC 地址格式不正确".toast()
                } else if (d == null) {
                    "请先选择设备型号".toast()
                } else {
                    scope.launch {
                        AppDatabase.instance.device().insert(
                            Device(
                                name = name.ifBlank { d.displayName },
                                mac = mac.trim().uppercase(),
                                key = key.trim(),
                                driverId = d.id,
                            )
                        )
                    }
                    dialogState.dismiss()
                }
            }) {
                Text(text = "确定")
            }
            TextButton(onClick = { dialogState.dismiss() }) {
                Text(text = "取消")
            }
        },
        icon = Icons.Filled.Watch,
        title = "添加设备",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                OutlinedButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        driver?.displayName ?: "选择型号（必选）",
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    SuitekiManager.driverList().forEach { d ->
                        DropdownMenuItem(
                            text = { Text(d.displayName) },
                            onClick = {
                                applyDriver(d)
                                menuExpanded = false
                            },
                        )
                    }
                }
            }

            TextField(
                value = name,
                onValueChange = {
                    name = it
                    nameEdited = it.isNotBlank()
                },
                label = { Text("备注名") },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = mac,
                    onValueChange = { mac = it.replace("：", ":") },
                    label = { Text("MAC 地址") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { bleScanState.show() }) { Text("扫描") }
            }

            if (driver?.needsKey == true) {
                TextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("授权密钥（32 位十六进制）") },
                )
            } else if (driver != null) {
                Text("该型号不需要授权密钥", style = MaterialTheme.typography.bodySmall)
            }


            Card({ scanDialogState.show() }, Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(15.dp)
                ) {
                    Text("通过小米运动健康添加", modifier = Modifier.align(Alignment.CenterStart))
                    Icon(
                        Icons.Default.ArrowForward,
                        null,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }
}


@Composable
fun DeviceEditDialog(dialogState: DialogState, device: Device) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(device.name) }
    var mac by remember { mutableStateOf(device.mac) }
    var key by remember { mutableStateOf(device.key) }
    var driver by remember { mutableStateOf(SuitekiManager.driver(device.driverId)) }
    var menuExpanded by remember { mutableStateOf(false) }
    SuitekiDialog(
        state = dialogState,
        onDismissRequest = {},
        button = {
            Button(onClick = {
                val d = driver
                if (!MAC_PATTERN.matches(mac.trim())) {
                    "MAC 地址格式不正确".toast()
                } else if (d == null) {
                    "请先选择设备型号".toast()
                } else {
                    scope.launch {
                        AppDatabase.instance.device().insert(
                            Device(
                                name = name.ifBlank { d.displayName },
                                mac = mac.trim().uppercase(),
                                key = key.trim(),
                                driverId = d.id,
                            ).also { it.index = device.index }
                        )
                    }
                    dialogState.dismiss()
                }
            }) {
                Text(text = "确定")
            }
            OutlinedButton(onClick = { dialogState.dismiss() }) {
                Text(text = "取消")
            }
        },
        icon = Icons.Filled.Watch,
        title = "修改设备",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                OutlinedButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        driver?.displayName ?: "选择型号（必选）",
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    SuitekiManager.driverList().forEach { d ->
                        DropdownMenuItem(
                            text = { Text(d.displayName) },
                            onClick = {
                                driver = d
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
            TextField(value = name, onValueChange = { name = it }, label = { Text("备注名") })
            TextField(value = mac,
                onValueChange = { mac = it.replace("：", ":") },
                label = { Text("MAC 地址") })
            if (driver?.needsKey == true) {
                TextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("授权密钥（32 位十六进制）") })
            } else if (driver != null) {
                Text("该型号不需要授权密钥", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

}
