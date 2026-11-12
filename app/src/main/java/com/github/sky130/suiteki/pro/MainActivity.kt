package com.github.sky130.suiteki.pro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.sky130.suiteki.pro.basic.SuitekiActivity
import com.github.sky130.suiteki.pro.ui.theme.SuitekiTheme
import com.ramcosta.composedestinations.DestinationsNavHost
import com.ramcosta.composedestinations.generated.NavGraphs


/**
 * 启动门槛只有蓝牙。
 *
 * 之前这里同时卡了「所有文件访问」和存储读写，用户拒绝就只剩一块空白画布；
 * 现在文件读取改走系统选择器（见 DeviceScreen 的日志导入），不再需要任何存储权限。
 */
class MainActivity : SuitekiActivity() {

    /** 由 Activity 生命周期驱动，Compose 直接读这个状态重组 */
    private var bleGranted by mutableStateOf(true)

    /** 本进程内是否已自动弹过一次系统授权框，避免拒绝后反复弹 */
    private var autoAsked = false

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 11 及以下没有 BLUETOOTH_SCAN，系统规定扫描 BLE 必须给定位权限
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun checkGranted(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 勾了「不再询问」之后系统不会再弹框，launch 也不会有任何回调。
     * 这时候只能引导用户去设置页，否则按钮点了像坏了一样。
     */
    private fun allPermanentlyDenied(): Boolean =
        autoAsked && requiredPermissions.none {
            ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }
    }

    @Composable
    override fun Content() {
        SuitekiTheme {
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { bleGranted = checkGranted() }

            LaunchedEffect(Unit) {
                bleGranted = checkGranted()
                if (!bleGranted && !autoAsked) {
                    autoAsked = true
                    launcher.launch(requiredPermissions)
                }
            }

            if (bleGranted) {
                DestinationsNavHost(navGraph = NavGraphs.root)
            } else {
                PermissionGate(
                    needsLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.S,
                    onRequest = {
                        if (allPermanentlyDenied()) openAppSettings()
                        else launcher.launch(requiredPermissions)
                    },
                    onOpenSettings = { openAppSettings() },
                    onExit = { finish() }
                )
            }
        }
    }

    override fun init() {
        bleGranted = checkGranted()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置返回后自动放行，不需要用户再点一次
        bleGranted = checkGranted()
    }
}

/**
 * 拒绝授权后的说明页。
 * 必须给出「去设置」和「退出」两个出口，否则永久拒绝的用户会卡死在空白页。
 */
@Composable
private fun PermissionGate(
    needsLocation: Boolean,
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
                "Re:Suiteki Pro 通过蓝牙连接手环：扫描附近设备、读取型号、安装表盘与固件。" +
                    "没有这项权限，应用无法工作。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (needsLocation) {
                Text(
                    "你的系统版本（Android 11 及以下）规定扫描蓝牙必须同时授予定位权限。" +
                        "本应用只用它发现手环，不读取、不保存任何位置信息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
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
