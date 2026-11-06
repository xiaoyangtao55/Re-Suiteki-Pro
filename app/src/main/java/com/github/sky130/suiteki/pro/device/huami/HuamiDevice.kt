@file:Suppress("LeakingThis")

package com.github.sky130.suiteki.pro.device.huami

import com.github.sky130.suiteki.pro.logic.ble.AbstractSuitekiDevice
import com.github.sky130.suiteki.pro.logic.ble.DeviceStatus
import com.github.sky130.suiteki.pro.logic.ble.InstallStatus
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.util.BytesUtils
import kotlinx.coroutines.flow.MutableStateFlow

open class HuamiDevice(name: String, mac: String, key: String) : AbstractSuitekiDevice(
    name, mac, key
) {
    override val version = MutableStateFlow("unknown")
    override val battery = MutableStateFlow("unknown")
    override val status = MutableStateFlow(DeviceStatus.Waiting)
    private val authService = HuamiAuthService(this)
    private val appListHelper = HuamiAppListHelper(this)
    private val bleSupport = HuamiBleSupport(this)
    private var installHelper: HuamiInstallHelper? = null
    private var authCallback: (() -> Unit)? = null
    override val appList get() = appListHelper.list
    val uuids = mapOf(
        HuamiService.UUID_SERVICE_MIBAND_SERVICE to listOf(
            HuamiService.UUID_CHARACTERISTIC_AUTH_NOTIFY,
            HuamiService.UUID_CHARACTERISTIC_APP
        ),
        HuamiService.UUID_SERVICE_FIRMWARE to listOf(HuamiService.UUID_CHARACTERISTIC_FIRMWARE_NOTIFY)
    )

    override fun onStart() {
        bleSupport.start()
    }

    /**
     * @param callback 认证成功后的回调。安装过程中遇到 10D203（认证失效）时用它恢复流程，
     *                 1.5.1 的 ReAuthCallback 就是这个作用。
     */
    fun auth(callback: (() -> Unit)? = null) {
        authCallback = callback
        status.value = DeviceStatus.Authing
        authService.startAuth()
    }

    fun onAuth() {
        status.value = DeviceStatus.Connected
        val callback = authCallback
        authCallback = null
        callback?.invoke()
        appListHelper.requestAppItems()
    }

    /**
     * BLE 链路就绪即可用。
     *
     * Band 7 的固件/表盘通道 0x1530/1531/1532 是老的文件传输服务（手环 3 时代就有），
     * 固件不校验 authKey，1.8.x 全程不做 0x0082 握手也能正常装表盘。所以这里不再等认证，
     * 连上就算 Connected；同时也不主动拉 appList（那条走 0x0082，未认证只会白等超时）。
     */
    fun onChannelReady() {
        status.value = DeviceStatus.Connected
    }

    fun onConnectFailed(reason: String) {
        status.value = DeviceStatus.Disconnect
        SuitekiManager.installStatus.value = InstallStatus.InstallFailure(0, reason)
    }

    /**
     * 设备明确拒绝了认证（比如 10 05 25 密钥不匹配）。
     * 必须和"连不上"区分开，否则用户只会看到看门狗超时后的"设备无响应"，永远查不到真正原因。
     */
    fun onAuthFailure(reason: String) {
        status.value = DeviceStatus.AuthFailure
        authCallback = null
        SuitekiManager.installStatus.value = InstallStatus.InstallFailure(0, reason)
    }

    fun handleChannel(data: ByteArray, service: String, characteristics: String) {
        authService.handleData(data, service, characteristics)
        if (service == HuamiService.UUID_SERVICE_FIRMWARE && characteristics == HuamiService.UUID_CHARACTERISTIC_FIRMWARE_NOTIFY) {
            installHelper?.handleBytes(data)
        }
    }

    fun handlePayload(data: ByteArray) {
        appListHelper.handlePayload(data)
    }

    /** 断线时必须清干净，否则 UI 会一直显示"已连接"，安装器也会挂在旧状态上 */
    open fun onDisconnect() {
        val wasInstalling = installHelper != null ||
                SuitekiManager.installStatus.value is InstallStatus.Installing
        status.value = DeviceStatus.Disconnect
        authCallback = null
        installHelper?.cancel()
        installHelper = null
        if (wasInstalling) {
            SuitekiManager.installStatus.value =
                InstallStatus.InstallFailure(0, "连接已断开，请重连后重试")
        }
    }

    override fun install(bytes: ByteArray) {
        install(bytes, HuamiService.TYPE_D2_WATCHFACE)
    }

    override fun install(bytes: ByteArray, type: Byte) {
        if (status.value != DeviceStatus.Connected) {
            SuitekiManager.installStatus.value =
                InstallStatus.InstallFailure(0, "设备未连接，请稍候重试")
            return
        }
        installHelper?.cancel()
        installHelper = HuamiInstallHelper(this, bytes, type, bleSupport.mtu)
        installHelper?.start()
    }

    override fun deleteApp(id: String) {
        appListHelper.uninstall(id)
    }

    override fun requestAppList() {
        appListHelper.requestAppItems()
    }

    override fun launchApp(id: String) {
        appListHelper.launch(id)
    }

    fun installFinish() {
        installHelper?.cancel()
        installHelper = null
    }

    fun write(data: ByteArray, service: String, characteristics: String) {
        SuitekiManager.log("onWrite", service, characteristics, BytesUtils.bytesToHexStr(data).toString())
        bleSupport.write(data, service, characteristics)
    }

    fun write(
        type: Short, data: ByteArray, extendedFlags: Boolean, encrypt: Boolean
    ) = authService.huamiWrite(type, data, extendedFlags, encrypt)
}
