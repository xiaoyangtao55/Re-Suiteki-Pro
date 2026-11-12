@file:Suppress("LeakingThis")

package com.github.sky130.suiteki.pro.device.xiaomi

import androidx.compose.runtime.snapshots.SnapshotStateList
import com.github.sky130.suiteki.pro.logic.ble.AbstractSuitekiDevice
import com.github.sky130.suiteki.pro.logic.ble.DeviceStatus
import com.github.sky130.suiteki.pro.logic.ble.HardwareInfo
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.logic.ble.WatchfaceInfo
import com.github.sky130.suiteki.pro.proto.xiaomi.XiaomiProto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

abstract class XiaomiDevice(name: String, mac: String, key: String) : AbstractSuitekiDevice(
    name, mac, key
) {
    override val hardware = MutableStateFlow<HardwareInfo?>(null)
    override val battery = MutableStateFlow("unknown")
    override val status = MutableStateFlow(DeviceStatus.Waiting)
    abstract val isEncrypted: Boolean
    internal val authService = XiaomiAuthService(this)
    abstract val support: XiaomiAbstractSupport
    private val job = Job()
    private val appListHelper = XiaomiAppListHelper(this)
    private val systemHelper = XiaomiSystemHelper(this)
    private val watchfaceHelper = XiaomiWatchfaceHelper(this)
    private var installHelper: XiaomiInstallHelper? = null
    override val appList get() = appListHelper.list
    override val watchfaceList: SnapshotStateList<WatchfaceInfo> get() = watchfaceHelper.list
    override val supportsWatchfaces: Boolean get() = true
    val scope = CoroutineScope(job)

    open fun handleCommand(command: XiaomiProto.Command) {
        SuitekiManager.log("handleCommand", command.toString())
        authService.handleCommand(command)
        systemHelper.handleCommand(command)
        appListHelper.handleCommand(command)
        watchfaceHelper.handleCommand(command)
        installHelper?.handleCommand(command)
    }

    fun auth() {
        status.value = DeviceStatus.Authing
        if (isEncrypted) {
            authService.startEncryptedHandshake()
        } else {
            authService.startClearTextHandshake()
        }
    }

    open fun onDisconnect() {
        status.value = DeviceStatus.Disconnect
    }

    /** 切设备 / 删设备前调用：SPP 要连 socket 和读写线程一起收掉 */
    override fun disconnect() {
        support.terminate()
        onDisconnect()
    }

    internal fun applyHardware(info: HardwareInfo) {
        if (info.isEmpty) return
        // 分几次回包时不要把已有字段覆盖成空
        val old = hardware.value ?: HardwareInfo()
        hardware.value = HardwareInfo(
            firmware = info.firmware.ifBlank { old.firmware },
            model = info.model.ifBlank { old.model },
            serial = info.serial.ifBlank { old.serial },
        )
    }

    /**
     * 认证完成后一次性把「设备是什么」问清楚：
     * 设备信息走 2/2，RPK 列表走 20/0，表盘列表走 4/0。
     * 这三条都只在认证后有效，提前发会被设备静默丢弃。
     */
    open fun onAuth() {
        systemHelper.request()
        appListHelper.requestRpkList()
        watchfaceHelper.requestList()
    }

    open fun onInstallFinish() {
        installHelper = null
        // 装完刷新一次，否则刚装上的表盘/小应用在列表里看不到
        systemHelper.request()
        watchfaceHelper.requestList()
    }

    override fun deleteApp(id: String) {
        appListHelper.delete(id)
    }

    override fun requestAppList() {
        appListHelper.requestRpkList()
    }

    override fun requestWatchfaceList() {
        watchfaceHelper.requestList()
    }

    override fun deleteWatchface(id: String) {
        watchfaceHelper.delete(id)
    }

    override fun onStart() {
        support.start()
    }

    override fun install(bytes: ByteArray) {
        installHelper = XiaomiInstallHelper(this, bytes).apply {
            install()
        }
    }
}
