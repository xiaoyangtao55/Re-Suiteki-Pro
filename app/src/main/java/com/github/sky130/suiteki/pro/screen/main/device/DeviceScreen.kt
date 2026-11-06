package com.github.sky130.suiteki.pro.screen.main.device

import android.os.Environment
import android.util.Log
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
import androidx.compose.ui.unit.dp
import com.clj.fastble.BleManager
import com.clj.fastble.callback.BleScanCallback
import com.clj.fastble.data.BleDevice
import com.clj.fastble.scan.BleScanRuleConfig
import com.github.sky130.suiteki.pro.MainApplication.Companion.context
import com.github.sky130.suiteki.pro.MainApplication.Companion.toast
import com.github.sky130.suiteki.pro.logic.ble.DeviceDriver
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager.waitForAuth
import com.github.sky130.suiteki.pro.logic.database.AppDatabase
import com.github.sky130.suiteki.pro.logic.database.model.Device
import com.github.sky130.suiteki.pro.screen.main.MainGraph
import com.github.sky130.suiteki.pro.screen.main.more.AppCard
import com.github.sky130.suiteki.pro.ui.widget.BaseSuitekiDialog
import com.github.sky130.suiteki.pro.ui.widget.DialogState
import com.github.sky130.suiteki.pro.ui.widget.SuitekiScaffold
import com.github.sky130.suiteki.pro.ui.widget.SuitekiDialog
import com.github.sky130.suiteki.pro.ui.widget.SuitekiTopBar
import com.github.sky130.suiteki.pro.ui.widget.rememberDialogState
import com.github.sky130.suiteki.pro.util.TextUtils
import com.github.sky130.suiteki.pro.util.ZipUtils
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.generated.destinations.FolderScreenDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

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
        SuitekiTopBar(title = "设备")
    }) {
        ScanLogDialog(state = scanDialogState)
        DeviceAddDialog(addDialogState, scanDialogState)
        Column {
            val list by AppDatabase.instance.device().getList().collectAsState(initial = listOf())
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(horizontal = 10.dp),
            ) {
                items(list) {
                    val editDialogState = rememberDialogState()
                    DeviceEditDialog(editDialogState, device = it)
                    ElevatedCard(
                        onClick = {
                            SuitekiManager.connect(it)
                            // 匹配不到驱动时不再静默返回，直接把原因告诉用户
                            SuitekiManager.connectError.value?.toast()
                        }, modifier = Modifier.fillMaxWidth()
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
    if (!state.visible.value) return
    val scope = rememberCoroutineScope()
    BaseSuitekiDialog(state = state, onDismissRequest = {
        state.dismiss()
    }, confirmButton = {
        TextButton(onClick = {
            "开始扫描".toast()
            scope.launch(Dispatchers.IO) {
                val texts = mutableListOf<String>()

                // 1) 直读小米运动健康 / 小米运动的日志。
                //    1.8.x 从来没让用户"填"过密钥，key 是它自己从 Wearable.log 里捞出来的；
                //    Pro 少了这一步，空 key 只能落到默认值，设备必然回 10 05 25。
                val directLogs = listOf(
                    "Android/data/com.xiaomi.wearable/files/log/Wearable.log",
                    "Android/data/com.mi.health/files/log/XiaomiFit.device.log"
                )
                for (path in directLogs) {
                    val f = File(Environment.getExternalStorageDirectory(), path)
                    if (!f.isFile) continue
                    runCatching { texts += f.readText() }
                        .onFailure { Log.w("ScanLog", "读取 ${f.absolutePath} 失败", it) }
                }

                // 2) 兜底：Download/wearablelog 下的日志包。不再写死内部文件名——
                //    新版小米运动健康导出的是 Wearable.log，旧版才叫 XiaomiFit.device.log。
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "wearablelog"
                )
                val zips = (dir.listFiles { _, name -> name.endsWith("log.zip", true) } ?: emptyArray())
                    .sortedByDescending {
                        it.nameWithoutExtension.replace("log", "").toLongOrNull() ?: 0L
                    }
                for (zip in zips.take(3)) {
                    runCatching {
                        ZipFile(zip).use { zf ->
                            zf.entries().asSequence()
                                .filter { !it.isDirectory && it.name.endsWith(".log", true) }
                                .forEach { e ->
                                    zf.getInputStream(e).bufferedReader()
                                        .use { r -> texts += r.readText() }
                                }
                        }
                    }.onFailure { Log.w("ScanLog", "解包 ${zip.name} 失败", it) }
                }

                if (texts.isEmpty()) {
                    return@launch withContext(Dispatchers.Main) {
                        "没找到日志：请按上面的步骤在小米运动健康里开启日志上传，或到系统设置里授予“所有文件访问”权限".toast()
                    }
                }

                val deviceList = arrayListOf<Device>()
                for (text in texts) {
                    for (i in text.split("\n")) {
                            val mac = TextUtils.getRegexMatchText(i, "mac=", ", ")
                            if (mac.isEmpty() || mac.length < 15) continue
                            val authKey = TextUtils.getRegexMatchText(i, "authKey=", ", ")
                            val model = TextUtils.getRegexMatchText(i, "model='", "', ")
                            val encryptKey = TextUtils.getRegexMatchText(i, "encryptKey=", ", ")
                            val end = mac.substring(12, 14) + mac.substring(15)
                            when (model) {
                                "miwear.watch.m66",
                                "miwear.watch.m66nfc",
                                "miwear.watch.m66tc",
                                "miwear.watch.m66dsn",
                                "miwear.watch.m66gl",
                                "miwear.watch.m66gln" -> {
                                    deviceList.add(
                                        Device(
                                            "Xiaomi Smart Band 8 $end",
                                            mac,
                                            encryptKey,
                                            "band8"
                                        )
                                    )
                                }

                                "lchz.watch.m67",
                                "lchz.watch.m67gl",
                                "lchz.watch.m67ys" -> {
                                    deviceList.add(
                                        Device(
                                            "Xiaomi Smart Band 8 Pro $end",
                                            mac,
                                            encryptKey,
                                            "band8pro"
                                        )
                                    )
                                }

                                "hmpace.watch.v7",
                                "hmpace.watch.v7nfc" -> {
                                    deviceList.add(
                                        Device(
                                            "Xiaomi Smart Band 7 $end",
                                            mac,
                                            authKey,
                                            "band7"
                                        )
                                    )
                                }

                                "lchz.watch.n65",
                                "lchz.watch.n65gl" -> {
                                    deviceList.add(
                                        Device(
                                            "Redmi Watch 4 $end",
                                            mac,
                                            encryptKey,
                                            "redmiwatch4"
                                        )
                                    )
                                }


                                "lchz.watch.n65acn",
                                "lchz.watch.n65agl",
                                "lchz.watch.n65ain" -> {
                                    deviceList.add(
                                        Device(
                                            "Redmi Watch 4 $end Active",
                                            mac,
                                            encryptKey,
                                            "redmiwatch4"
                                        )
                                    )
                                }

                                "mijia.watch.n62,",
                                "mjjia.watch.n62lte",
                                "mijia.watch.n62cg" -> {
                                    deviceList.add(
                                        Device(
                                            "Xiaomi Watch S3 $end Active",
                                            mac,
                                            encryptKey,
                                            "watchs3"
                                        )
                                    )
                                }

                            }
                        }
                }

                // 同 mac 只更新密钥，不再插一条重复记录；用户加过的设备直接就能用新 key
                val existing = AppDatabase.instance.device().getAll()
                val toInsert = arrayListOf<Device>()
                var updated = 0
                for (d in deviceList) {
                    val hit = existing.firstOrNull { it.mac.equals(d.mac, ignoreCase = true) }
                    if (hit == null) {
                        if (toInsert.none { it.mac.equals(d.mac, ignoreCase = true) }) toInsert += d
                    } else if (d.key.isNotBlank() && hit.key != d.key) {
                        AppDatabase.instance.device().updateKey(hit.mac, d.key)
                        updated++
                    }
                }
                if (toInsert.isNotEmpty()) AppDatabase.instance.device().insert(toInsert)

                val withKey = deviceList.count { it.key.isNotBlank() }
                withContext(Dispatchers.Main) {
                    when {
                        deviceList.isEmpty() ->
                            "日志里没有设备记录，请确认小米运动健康里绑定过这台手环".toast()
                        withKey == 0 ->
                            "找到 ${deviceList.size} 台设备，但日志里没有 authKey，无法用于华米认证".toast()
                        else -> buildString {
                            append("扫描完成：${deviceList.size} 台，带密钥 $withKey 台")
                            if (toInsert.isNotEmpty()) append("，新增 ${toInsert.size} 台")
                            if (updated > 0) append("，更新密钥 $updated 台")
                        }.toast()
                    }
                }
            }
        }) {
            Text(text = "扫描")
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
            在开始扫描日志文件之前, 需要您完成下面的操作:
            1.将小米运动健康结束后台运行(如果实在不了解"后台运行"是什么,可以选择将手机重启)
            2.进入小米运动健康
            3.点击右下角的[我的],滑动到底部点击[关于]
            4.连续点击那个[橙色圆圈](小米应用图标)
            5.提示对话框出来时,请点击确认
            6.对话框消失后,进入Suiteki Pro
            7.点击下面的[扫描]按钮
        """.trimIndent()
        }

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (i in about.split("\n")) {
                Text(text = i)
            }
        }
    })
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
    val devices = remember { mutableStateListOf<BleDevice>() }
    var scanning by remember { mutableStateOf(true) }

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
            Text(
                if (scanning) "让手环靠近手机并保持屏幕点亮" else "没扫到设备，确认蓝牙已开启后重试",
                modifier = Modifier.padding(16.dp),
            )
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
