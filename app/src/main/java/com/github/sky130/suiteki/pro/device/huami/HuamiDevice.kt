@file:Suppress("LeakingThis")

package com.github.sky130.suiteki.pro.device.huami

import com.github.sky130.suiteki.pro.logic.ble.AbstractSuitekiDevice
import com.github.sky130.suiteki.pro.logic.ble.BatteryInfo
import com.github.sky130.suiteki.pro.logic.ble.DeviceStatus
import com.github.sky130.suiteki.pro.logic.ble.HardwareInfo
import com.github.sky130.suiteki.pro.logic.ble.InstallStatus
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.util.AppNameStore
import com.github.sky130.suiteki.pro.util.BytesUtils
import com.github.sky130.suiteki.pro.util.InstallRecordStore
import com.github.sky130.suiteki.pro.util.ZabManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

open class HuamiDevice(name: String, mac: String, key: String) : AbstractSuitekiDevice(
    name, mac, key
) {
    override val hardware = MutableStateFlow<HardwareInfo?>(null)
    override val battery = MutableStateFlow<BatteryInfo?>(null)
    override val status = MutableStateFlow(DeviceStatus.Waiting)
    private val authService = HuamiAuthService(this)
    private val appListHelper = HuamiAppListHelper(this)
    private val bleSupport = HuamiBleSupport(this)
    private var installHelper: HuamiInstallHelper? = null
    private var authCallback: (() -> Unit)? = null
    /** 安装中留下、等 0x00A0 列表回包确认的记录 */
    private var pendingInstall: PendingInstall? = null
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

    /** 切设备 / 删设备前调用：先停 GATT，再把状态和安装器清干净 */
    override fun disconnect() {
        bleSupport.disconnect()
        onDisconnect()
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
     * 连上就算 Connected。
     */
    fun onChannelReady() {
        status.value = DeviceStatus.Connected
        // 0x00a0 是明文列表，未认证也能读，和 0x0082 那条不是一回事。
        // 先拉一次存下当前 id 集合：装完再拉，差集就是刚装进去的那个。
        appListHelper.requestAppItems()
    }

    /**
     * BLE 设备信息服务（0x180A）读回来的结果。
     * 有的机型只提供其中一两个特征，缺失的字段留空，不能当成读取失败。
     */
    fun onHardwareInfo(firmware: String?, model: String?, serial: String?) {
        val info = HardwareInfo(
            // 固件串有的带前缀 V（GB 也是这么剥的）
            firmware = firmware?.trim()?.removePrefix("V").orEmpty(),
            model = model?.trim().orEmpty(),
            serial = serial?.trim().orEmpty(),
        )
        if (info.isEmpty) return
        val old = hardware.value ?: HardwareInfo()
        hardware.value = HardwareInfo(
            firmware = info.firmware.ifBlank { old.firmware },
            model = info.model.ifBlank { old.model },
            serial = info.serial.ifBlank { old.serial },
        )
    }

    /**
     * 标准电池服务（0x180F/2A19）读回来的百分比。
     * 协议里只有一个字节，没有充电状态；读不到就传 null，界面不显示这一行。
     */
    fun onBatteryLevel(level: Int?) {
        if (level == null) return
        if (level !in 0..100) return
        val old = battery.value
        battery.value = BatteryInfo(level = level, charging = old?.charging)
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
        // 断线后固件版本可能随固件升级而变化，留着旧值会误导
        hardware.value = null
        if (wasInstalling) {
            SuitekiManager.installStatus.value =
                InstallStatus.InstallFailure(0, "连接已断开，请重连后重试")
        }
    }

    override fun install(bytes: ByteArray) {
        install(bytes, HuamiService.TYPE_D2_WATCHFACE, "")
    }

    override fun install(bytes: ByteArray, type: Byte, fileName: String) {
        if (status.value != DeviceStatus.Connected) {
            SuitekiManager.installStatus.value =
                InstallStatus.InstallFailure(0, "设备未连接，请稍候重试")
            return
        }
        if (type == HuamiService.TYPE_D2_APP) rememberAppName(bytes)
        // 记下安装前的 id 快照：装完设备列表会多出这个包，差集就是它
        pendingInstall = PendingInstall(
            fileName = fileName.substringBeforeLast('.').trim().ifBlank { "未命名" },
            type = type,
            bytes = bytes,
            beforeIds = appList.map { it.id }.toSet(),
            startedAt = System.currentTimeMillis(),
        )
        installHelper?.cancel()
        installHelper = HuamiInstallHelper(this, bytes, type, bleSupport.mtu)
        installHelper?.start()
    }

    /**
     * 安装时顺手把包里的 app.json 读出来，记下 id 和名字。
     * 0x00A0 列表只有 id，靠这一步以后才能在应用管理里看到人类可读的名字。
     *
     * 已经有名字（内置表或用户手动命名）的不覆盖：表盘包的 appName 经常是
     * "test"，或者和下载时的标题对不上，人工核对过的更可信。
     */
    private fun rememberAppName(bytes: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            val meta = ZabManifest.parse(bytes) ?: return@launch
            if (!isMeaningfulName(meta.name)) {
                SuitekiManager.log("app-name-skip", "${meta.id} name=${meta.name}")
                return@launch
            }
            if (AppNameStore.get(meta.id) != null) return@launch
            AppNameStore.put(meta.id, meta.name)
            SuitekiManager.log("app-name", "${meta.id}=${meta.name} ${meta.version}")
        }
    }

    private fun isMeaningfulName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.isNotEmpty() && n !in setOf("test", "demo", "untitled", "watchface", "app")
    }

    /**
     * 0x00A0 列表回包。
     *
     * 表盘 `.bin` 里没有 appId，装完只能靠「安装前后的列表差集」认出是哪个 id，
     * 再用文件名当名字。小程序优先用 app.json 里的名字。
     * 安装前没拿到列表快照（beforeIds 为空）时不猜，避免把名字配到别人头上。
     */
    fun onAppListParsed(ids: List<String>) {
        val pending = pendingInstall ?: return
        if (!pending.armed) return
        if (System.currentTimeMillis() - pending.startedAt > PENDING_TIMEOUT_MS) {
            pendingInstall = null
            return
        }
        val kind = when (pending.type) {
            HuamiService.TYPE_D2_WATCHFACE -> InstallRecordStore.KIND_WATCHFACE
            HuamiService.TYPE_D2_APP -> InstallRecordStore.KIND_APP
            else -> {
                pendingInstall = null
                return
            }
        }
        if (pending.beforeIds.isEmpty()) {
            pendingInstall = null
            return
        }
        val target = addedIds(pending.beforeIds, ids).firstOrNull() ?: return
        pendingInstall = null
        CoroutineScope(Dispatchers.IO).launch {
            val meta = if (pending.type == HuamiService.TYPE_D2_APP) {
                ZabManifest.parse(pending.bytes)
            } else {
                null
            }
            val name = meta?.name?.takeIf { isMeaningfulName(it) } ?: pending.fileName
            InstallRecordStore.put(target, name, kind)
            // app.json 里的 id 与列表 id 理论上一致；不一致时两边都写，按哪个都能查得到
            if (meta != null && !meta.id.equals(target, ignoreCase = true)) {
                InstallRecordStore.put(meta.id, name, kind)
            }
            SuitekiManager.log("install-record", "$target=$name kind=$kind")
        }
    }

    override fun deleteApp(id: String) {
        appListHelper.uninstall(id)
        InstallRecordStore.remove(id)
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
        // 装完了才允许回包参与比对：安装中途用户点刷新不能算数
        pendingInstall?.armed = true
        appListHelper.requestAppItems()
        // 设备把新包装进列表可能慢半拍，1.5 秒后补问一次；已经认出来就不用了
        if (pendingInstall != null) {
            CoroutineScope(Dispatchers.Main).launch {
                delay(1500)
                if (pendingInstall?.armed == true) appListHelper.requestAppItems()
            }
        }
    }

    fun write(data: ByteArray, service: String, characteristics: String) {
        SuitekiManager.log("onWrite", service, characteristics, BytesUtils.bytesToHexStr(data).toString())
        bleSupport.write(data, service, characteristics)
    }

    fun write(
        type: Short, data: ByteArray, extendedFlags: Boolean, encrypt: Boolean
    ) = authService.huamiWrite(type, data, extendedFlags, encrypt)

    /**
     * 一次安装的待确认记录。安装成功前 [armed] 一直是 false，
     * 避免用户在安装过程中点刷新，把中途的列表回包误当成安装结果。
     */
    private class PendingInstall(
        val fileName: String,
        val type: Byte,
        val bytes: ByteArray,
        val beforeIds: Set<String>,
        val startedAt: Long,
    ) {
        var armed = false
    }

    companion object {
        /** 安装失败或设备不回列表时，超过这个时间就放弃这条待确认记录 */
        private const val PENDING_TIMEOUT_MS = 60_000L

        /**
         * 安装前后的列表差集。没有安装前快照（空集合）就返回空，宁可留空让用户手动命名，
         * 也不要把名字随便配给一个已有条目。
         */
        fun addedIds(before: Set<String>, now: List<String>): List<String> {
            if (before.isEmpty()) return emptyList()
            return now.filter { it !in before }
        }
    }
}
