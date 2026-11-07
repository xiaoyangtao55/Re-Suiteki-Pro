package com.github.sky130.suiteki.pro.screen.main.home

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.AppRegistration
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.github.sky130.suiteki.pro.MainApplication
import com.github.sky130.suiteki.pro.MainApplication.Companion.toast
import com.github.sky130.suiteki.pro.logic.ble.AbstractSuitekiDevice
import com.github.sky130.suiteki.pro.logic.ble.DeviceStatus
import com.github.sky130.suiteki.pro.logic.ble.InstallStatus
import com.github.sky130.suiteki.pro.logic.ble.InstallType
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.logic.database.AppDatabase
import com.github.sky130.suiteki.pro.logic.database.model.Device
import com.github.sky130.suiteki.pro.screen.main.ExternalNavigator
import com.github.sky130.suiteki.pro.screen.main.LocalMainNavController
import com.github.sky130.suiteki.pro.screen.main.MainGraph
import com.github.sky130.suiteki.pro.ui.widget.AppCard
import com.github.sky130.suiteki.pro.ui.widget.DialogState
import com.github.sky130.suiteki.pro.ui.widget.SuitekiDialog
import com.github.sky130.suiteki.pro.ui.widget.SuitekiScaffold
import com.github.sky130.suiteki.pro.ui.widget.SuitekiTopBar
import com.github.sky130.suiteki.pro.ui.widget.rememberDialogState
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.NavGraph
import com.ramcosta.composedestinations.generated.destinations.AppScreenDestination
import com.ramcosta.composedestinations.generated.destinations.DeviceScreenDestination
import com.ramcosta.composedestinations.generated.destinations.FolderScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.navigation.navigate
import com.ramcosta.composedestinations.result.ResultRecipient
import com.ramcosta.composedestinations.result.onResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 主页。设备的选择与连接都在这里：
 * 未连接时列出库里已保存的设备，点一条即连；连上后换成当前设备卡片 + 功能入口。
 * 「设备」页只负责新增/编辑/删除，不再承担选择连接的职责。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Destination<MainGraph>(start = true)
fun HomeScreen(
    navigator: ExternalNavigator,
    resultRecipient: ResultRecipient<FolderScreenDestination, File>
) {
    val scope = rememberCoroutineScope()
    val installDialogState = rememberDialogState()
    val logDialogState = rememberDialogState()
    val aboutDialogState = rememberDialogState()
    val helpDialogState = rememberDialogState()
    var quoteIndex by remember { mutableIntStateOf(0) }

    resultRecipient.onResult { resultValue ->
        Log.d("TAG", "got result ${resultValue.name}")
        // 重置放在 show() 之前。原来是在对话框的 LaunchedEffect(Unit) 里清，
        // 文件小的时候安装进度可能先于首次组合写入，随后被清成 Nope，进度条停在 0。
        SuitekiManager.installStatus.value = InstallStatus.Nope
        installDialogState.show()
        scope.launch(Dispatchers.IO) {
            val bytes = resultValue.readBytes()
            // 表盘 .bin → 0x08，小程序 .zab/.zip → 0xA0，写错设备会回 10D656
            val type = InstallType.detect(resultValue.name, bytes)
            withContext(Dispatchers.Main) {
                val device = SuitekiManager.bleDevice.value
                when {
                    device == null ->
                        SuitekiManager.installStatus.value =
                            InstallStatus.InstallFailure(0, "请先连接设备")
                    device.status.value != DeviceStatus.Connected ->
                        SuitekiManager.installStatus.value =
                            InstallStatus.InstallFailure(0, "设备未连接，请稍候再试")
                    else -> device.install(bytes, type)
                }
            }
        }
    }

    AboutDialog(aboutDialogState, logDialogState)
    HelpDialog(helpDialogState)
    LogDialog(logDialogState)
    InstallDialog(state = installDialogState, log = logDialogState)

    // 同层页签要走 MainGraph 的控制器，外部那个只在跳 RootGraph 页面时用
    val mainNav = LocalMainNavController.current

    val current by SuitekiManager.bleDevice.collectAsState(null)
    // 连接状态在这里订阅一次，卡片和下面的功能入口共用；直接读 status.value
    // 不会触发重组，连上以后"暂不可用"那行会一直挂着。
    val status by remember(current) {
        current?.status ?: MutableStateFlow(DeviceStatus.Disconnect)
    }.collectAsState(DeviceStatus.Disconnect)
    val saved by AppDatabase.instance.device().getList().collectAsState(emptyList())
    val connectError by SuitekiManager.connectError.collectAsState(null)
    LaunchedEffect(connectError) {
        connectError?.let { it.toast() }
    }

    SuitekiScaffold(topBar = {
        SuitekiTopBar(title = "主页", actions = {
            IconButton(onClick = { aboutDialogState.show() }) {
                Icon(Icons.Outlined.Info, contentDescription = "关于")
            }
        })
    }) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OutlinedCard(
                    onClick = { quoteIndex++ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = hitokoto[quoteIndex % hitokoto.size],
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            val device = current
            if (device == null) {
                item { SectionTitle("未连接设备", "点下面的设备直接连接") }
                if (saved.isEmpty()) {
                    item {
                        EmptyDeviceCard {
                            mainNav.navigate(DeviceScreenDestination) {
                                launchSingleTop = true
                            }
                        }
                    }
                } else {
                    items(saved, key = { it.index }) { d ->
                        SavedDeviceCard(d) { SuitekiManager.connect(d) }
                    }
                }
            } else {
                item { ConnectedCard(device, status, saved) }
                val others = saved.filterNot { it.mac.equals(device.mac, ignoreCase = true) }
                if (others.isNotEmpty()) {
                    item { SectionTitle("切换设备") }
                    items(others, key = { it.index }) { d ->
                        SavedDeviceCard(d) { SuitekiManager.connect(d) }
                    }
                }
            }

            item {
                val connected = status == DeviceStatus.Connected
                val requireConnected = { action: () -> Unit ->
                    if (connected) action() else "请先在上方连接设备".toast()
                }
                FeatureGrid(
                    listOf(
                        Feature("安装", Icons.Default.FolderOpen) {
                            requireConnected {
                                navigator.navigator.navigate(FolderScreenDestination)
                            }
                        },
                        Feature("应用管理", Icons.Default.AppRegistration) {
                            requireConnected {
                                navigator.navigator.navigate(AppScreenDestination)
                            }
                        },
                        Feature("帮助", Icons.AutoMirrored.Outlined.HelpOutline) {
                            helpDialogState.show()
                        },
                        Feature("更新", Icons.Default.Update) {
                            MainApplication.openUrl(
                                "https://github.com/xiaoyangtao55/Re-Suiteki-Pro/releases"
                            )
                        },
                    )
                )
                if (!connected && current != null) {
                    Text(
                        "设备还没连上，安装和应用管理暂时不可用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}


/**
 * 需要设备但还没连接时的占位。应用管理、文件选择等页面共用。
 * 连接入口在主页，所以这里给一个返回按钮，而不是内嵌连接列表。
 */
@Composable
fun DisconnectScreen(onBack: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.Block,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("请先连接设备", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "连接入口在主页的「未连接设备」列表里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (onBack != null) {
            Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 已保存设备的一条。点一下就连，不做二次确认——连错了再点另一条即可。 */
@Composable
private fun SavedDeviceCard(device: Device, onClick: () -> Unit) {
    val model = SuitekiManager.driver(device.driverId)?.displayName
        ?: "未知型号，请到设备页编辑"
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    model,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    device.mac,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 当前连接。顺序是名称 → MAC → 连接状态，状态不再和 MAC 挤在一行。 */
@Composable
private fun ConnectedCard(
    device: AbstractSuitekiDevice,
    status: DeviceStatus,
    saved: List<Device>,
) {
    val model = saved
        .firstOrNull { it.mac.equals(device.mac, ignoreCase = true) }
        ?.let { SuitekiManager.driver(it.driverId)?.displayName }

    val statusColor = when (status) {
        DeviceStatus.Connected -> MaterialTheme.colorScheme.primary
        DeviceStatus.AuthFailure -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("当前连接", style = MaterialTheme.typography.labelMedium)
            Text(device.name, style = MaterialTheme.typography.titleLarge)
            if (model != null) {
                Text(
                    model,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                device.mac,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusColor, CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    status.label(),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
            }
        }
    }
}

private fun DeviceStatus.label() = when (this) {
    DeviceStatus.Connected -> "已连接"
    DeviceStatus.Disconnect -> "已断开"
    DeviceStatus.Authing -> "认证中…"
    DeviceStatus.Waiting -> "连接中…"
    DeviceStatus.AuthFailure -> "认证失败"
}

@Composable
private fun EmptyDeviceCard(onAdd: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("还没有添加设备", style = MaterialTheme.typography.titleMedium)
            Text(
                "先到设备页添加型号和 MAC，再回这里连接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onAdd) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("添加设备")
            }
        }
    }
}

private data class Feature(
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/** 两列入口。用 Row 手排而不是 LazyVerticalGrid，避免和外层 LazyColumn 嵌套滚动。 */
@Composable
private fun FeatureGrid(entries: List<Feature>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { feature ->
                    AppCard(
                        modifier = Modifier.weight(1f),
                        title = feature.title,
                        icon = feature.icon,
                        onClick = feature.onClick
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun InstallDialog(state: DialogState, log: DialogState) {
    SuitekiDialog(state = state, title = "安装文件", onDismissRequest = { }) {
        val status by SuitekiManager.installStatus
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                progress = { status.progress / 100f },
                modifier = Modifier.size(130.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 15.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round,
            )
            Spacer(modifier = Modifier.height(15.dp))
            when (status) {
                is InstallStatus.InstallSuccess -> {
                    Text(text = "安装完成", style = MaterialTheme.typography.titleLarge)
                }

                is InstallStatus.InstallFailure -> {
                    Text(text = "安装失败", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = (status as InstallStatus.InstallFailure).message,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                is InstallStatus.Installing -> {
                    Text(text = "${status.progress}%", style = MaterialTheme.typography.titleLarge)
                }

                InstallStatus.Nope -> {}
            }
            Button(onClick = { log.show() }, modifier = Modifier.padding(top = 15.dp)) {
                Text(text = "日志")
            }
            if (status is InstallStatus.InstallSuccess || status is InstallStatus.InstallFailure) {
                Button(onClick = { state.dismiss() }, modifier = Modifier.padding(top = 15.dp)) {
                    Text(text = "返回")
                }
            }
        }
    }
}
