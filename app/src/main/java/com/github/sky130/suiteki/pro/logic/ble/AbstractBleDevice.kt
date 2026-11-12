package com.github.sky130.suiteki.pro.logic.ble

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.flow.StateFlow

abstract class AbstractSuitekiDevice(
    val name: String,
    val mac: String,
    val key: String,
) {
    /**
     * 固件/型号/序列号。null 表示还没读到（没连上，或设备不回这条）。
     * 小米走厂商协议（2/2），华米走标准 GATT 设备信息服务（0x180A）。
     */
    abstract val hardware: StateFlow<HardwareInfo?>

    abstract val battery: StateFlow<String>
    abstract val status: StateFlow<DeviceStatus>
    abstract val appList: SnapshotStateList<AppInfo>
    abstract fun onStart()
    abstract fun install(bytes: ByteArray)

    /**
     * 表盘列表。目前只有小米系能拉（命令类型 4），其它设备恒为空。
     * 列表为空且 [supportsWatchfaces] 为 false 时，界面上不出现表盘入口。
     */
    open val watchfaceList: SnapshotStateList<WatchfaceInfo> = mutableStateListOf()
    open val supportsWatchfaces: Boolean get() = false

    /**
     * 带包类型的安装。只有华米系设备用得到（D2 头里的 type 字节），
     * 其它设备忽略 type，行为与 [install] 一致。
     */
    open fun install(bytes: ByteArray, type: Byte) = install(bytes)
    open fun deleteApp(id: String){}
    open fun launchApp(id:String){}
    open fun requestAppList(){}
    open fun requestWatchfaceList(){}
    open fun deleteWatchface(id: String){}

    /**
     * 主动断开并释放底层资源（BLE GATT / SPP socket 与读写线程）。
     * 切换设备、删除设备前必须调用，否则旧连接会一直占着通道：
     * 表现为"新连接上了但设备不响应"，线程数也会越用越多。
     */
    open fun disconnect() {}
}

data class AppInfo(val id: String,val name: String)

/** 设备自报的硬件信息，字段可能为空（部分机型不提供某几项） */
data class HardwareInfo(
    val firmware: String = "",
    val model: String = "",
    val serial: String = "",
) {
    val isEmpty: Boolean get() = firmware.isBlank() && model.isBlank() && serial.isBlank()
}

/**
 * 表盘条目。[active] 是当前正在用的，[canDelete] 由设备给出——
 * 内置表盘一律 false，界面上不该给删除按钮。
 */
data class WatchfaceInfo(
    val id: String,
    val name: String,
    val active: Boolean = false,
    val canDelete: Boolean = false,
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Suiteki(
    /** 稳定标识，会写进数据库。改名等于换设备类型，不要随手改。 */
    val id: String,
    /** 型号选择列表里给用户看的名字 */
    val displayName: String,
    /** 该型号是否需要用户填授权密钥（Band 7 不需要，小米 8 系需要） */
    val needsKey: Boolean = false,
    /**
     * 蓝牙广播名以这些前缀开头时，在扫描结果里把该型号排到第一并预选。
     * 仅用于推荐，不参与驱动判定——用户手动改名也不会导致连不上。
     */
    val hints: Array<String> = [],
)


enum class DeviceStatus {
    Connected, Disconnect, Authing, Waiting, AuthFailure
}
