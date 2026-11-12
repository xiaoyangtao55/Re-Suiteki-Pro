package com.github.sky130.suiteki.pro

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.sky130.suiteki.pro.MainApplication.Companion.toast
import com.github.sky130.suiteki.pro.basic.SuitekiActivity
import com.github.sky130.suiteki.pro.ui.theme.SuitekiTheme
import com.github.sky130.suiteki.pro.util.Permissions
import com.github.sky130.suiteki.pro.util.openAppSettings
import com.ramcosta.composedestinations.DestinationsNavHost
import com.ramcosta.composedestinations.generated.NavGraphs


/**
 * 启动门槛只有蓝牙连接。
 *
 * Android 12+ 要「附近设备」，11 及以下全是安装时权限，这里直接放行，
 * 定位等到用户点「扫描」时再去要（见 DeviceScreen 的 DeviceScanDialog）。
 */
class MainActivity : SuitekiActivity() {

    /** 由 Activity 生命周期驱动，Compose 直接读这个状态重组 */
    private var bleGranted by mutableStateOf(true)

    /** 本进程内是否已自动弹过一次系统授权框，避免拒绝后反复弹 */
    private var autoAsked = false

    /** 上一次 onResume 时是否还有权限，用于识别「用着用着被收回」 */
    private var hadPermission = false

    private fun checkGranted(): Boolean = Permissions.granted(this, Permissions.startup())

    @Composable
    override fun Content() {
        SuitekiTheme {
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {
                bleGranted = checkGranted()
                hadPermission = bleGranted
            }

            val startup = Permissions.startup()
            LaunchedEffect(Unit) {
                bleGranted = checkGranted()
                if (!bleGranted && !autoAsked && startup.isNotEmpty()) {
                    autoAsked = true
                    launcher.launch(startup)
                }
            }

            if (bleGranted) {
                DestinationsNavHost(navGraph = NavGraphs.root)
            } else {
                PermissionGate(
                    onRequest = {
                        if (Permissions.permanentlyDenied(this@MainActivity, startup, autoAsked)) {
                            openAppSettings()
                        } else {
                            launcher.launch(startup)
                        }
                    },
                    onOpenSettings = { openAppSettings() },
                    onExit = { finish() }
                )
            }
        }
    }

    override fun init() {
        bleGranted = checkGranted()
        hadPermission = bleGranted
    }

    override fun onResume() {
        super.onResume()
        val now = checkGranted()
        // 后台被系统或用户收回权限：说明一下，否则界面突然跳回授权页会很突兀
        if (hadPermission && !now) {
            "蓝牙权限已被收回，设备连接已断开".toast()
        }
        hadPermission = now
        bleGranted = now
    }
}

/**
 * 拒绝授权后的说明页。
 * 必须给出「去设置」和「退出」两个出口，否则永久拒绝的用户会卡死在空白页。
 */
@Composable
private fun PermissionGate(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                modifier = Modifier.padding(bottom = 4.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "需要蓝牙权限",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "Re:Suiteki Pro 通过蓝牙连接手环：读取型号、安装表盘与固件。" +
                    "没有这项权限，应用无法工作。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) { Text("授予权限") }
            TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("打开系统设置")
            }
            TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                Text("退出")
            }
        }
    }
}
