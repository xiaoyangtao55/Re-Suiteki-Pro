package com.github.sky130.suiteki.pro

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.github.sky130.suiteki.pro.basic.SuitekiActivity
import com.github.sky130.suiteki.pro.ui.theme.SuitekiTheme
import com.ramcosta.composedestinations.DestinationsNavHost
import com.ramcosta.composedestinations.generated.NavGraphs
import dev.shreyaspatil.permissionFlow.utils.launch
import dev.shreyaspatil.permissionflow.compose.rememberMultiplePermissionState
import dev.shreyaspatil.permissionflow.compose.rememberPermissionFlowRequestLauncher
import kotlinx.coroutines.delay


class MainActivity : SuitekiActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    @Preview
    override fun Content() {
        SuitekiTheme {
            val permissionLauncher = rememberPermissionFlowRequestLauncher()
            val list = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            } else {
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            }
            val state by rememberMultiplePermissionState(*list)
            LaunchedEffect(Unit) {
                requestExternalStorage()
            }
            if (state.allGranted) {
                // 全文件访问权限只影响"扫描小米日志"和文件选择器，不该作为启动门槛。
                // 原来这里用 isExternalStorageState 把整个界面挡住，用户拒绝授权就白屏。
                DestinationsNavHost(navGraph = NavGraphs.root)
            } else {
                Box(modifier = Modifier.background(MaterialTheme.colorScheme.background))
                LaunchedEffect(Unit) {
                    delay(100)
                    permissionLauncher.launch(*list)
                }
                return@SuitekiTheme
            }
        }
    }

    private var isExternalStorageState by mutableStateOf(false)

    /** 未授权时只在本次进程里自动跳一次系统设置页，避免每次 onResume 都弹 */
    private var storagePrompted = false

    override fun onResume() {
        super.onResume()
        requestExternalStorage()
    }

    private fun requestExternalStorage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            isExternalStorageState = true
            return
        }
        if (Environment.isExternalStorageManager()) {
            isExternalStorageState = true
            return
        }
        // 原来这里判断完又无条件置 true，等于门禁形同虚设；现在如实记录状态，
        // 但不再用它挡界面，只在首次缺失时引导一次。
        isExternalStorageState = false
        if (!storagePrompted) {
            storagePrompted = true
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }
    }

    override fun init() {

    }

}