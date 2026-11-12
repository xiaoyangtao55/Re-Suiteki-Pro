package com.github.sky130.suiteki.pro.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限的统一出口。
 *
 * 这里只有两组：启动就要的、扫描才要的。
 * Android 11 及以下没有「附近设备」权限，蓝牙两条是安装时权限，扫描则必须带定位，
 * 所以老系统启动时不申请任何东西，用户点「扫描」才弹——省得装完就被要定位，莫名其妙。
 */
object Permissions {

    /** 启动门槛：12+ 的扫描/连接权限；11 及以下为空（安装即授予，无需运行时申请） */
    fun startup(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyArray()
    }

    /** 扫描附近设备所需：12+ 同上；11 及以下系统强制要求定位 */
    fun scan(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun granted(context: Context, perms: Array<String>): Boolean = perms.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 勾了「不再询问」之后系统不再弹框，launch 也不会有回调。
     * 只有「申请过」且全部权限都不再显示 rationale 才能这么判断，
     * 否则首次进入会被误判成永久拒绝、直接跳设置页。
     */
    fun permanentlyDenied(activity: Activity?, perms: Array<String>, asked: Boolean): Boolean {
        if (activity == null || !asked || perms.isEmpty()) return false
        return perms.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
    }
}

/** 跳到本应用的系统设置页，权限被永久拒绝时的唯一出口 */
fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }
}
