package com.github.sky130.suiteki.pro.device.huami

import androidx.compose.runtime.mutableStateListOf
import com.github.sky130.suiteki.pro.logic.ble.AppInfo
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.util.BytesUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 手环 7 的应用列表，走 0x00a0（apps service）。
 *
 * 注意这**不是** 0x0026：0x0026 是主菜单显示项（心率、天气、闹钟那些卡片的排序），
 * 走加密通道，未认证读不到；0x00a0 是真正安装在手环里的小程序/表盘，默认明文，
 * Band 7 在没做 0x0082 认证时也能读。
 *
 * 请求 16 字节：02 01 01 00 + 12 个 0
 * 响应：02 00 01 ...，前 16 字节是头，之后是 "A1B2C3D4-1.0.0;E5F6..." 的 ASCII 串
 */
class HuamiAppListHelper(val device: HuamiDevice) {

    companion object {
        const val CMD_APPS: Byte = 0x02
        const val CMD_INCOMING: Byte = 0x00
        const val CMD_OUTGOING: Byte = 0x01

        const val CMD_APPS_LIST: Byte = 0x01
        const val CMD_APPS_DELETE: Byte = 0x03

        private const val REQUEST_SIZE = 16
        private const val LIST_HEADER_SIZE = 16
        private const val ID_LENGTH = 8
    }

    val list = mutableStateListOf<AppInfo>()

    fun handlePayload(payload: ByteArray) {
        if (payload.size < 3) return
        if (payload[0] != CMD_APPS || payload[1] != CMD_INCOMING) return
        when (payload[2]) {
            CMD_APPS_LIST -> parseAppList(payload)
        }
    }

    /**
     * 列表是纯 ASCII，没有长度前缀也没有结束符，末尾可能跟 0。
     * 形如 "0A1B2C3D-1.0.0;0A1B2C3E-2.1.3;"
     */
    private fun parseAppList(payload: ByteArray) {
        if (payload.size <= LIST_HEADER_SIZE) {
            list.clear()
            return
        }
        val text = String(
            payload, LIST_HEADER_SIZE, payload.size - LIST_HEADER_SIZE, Charsets.UTF_8
        ).trim { it <= ' ' || it == '\u0000' }

        val apps = ArrayList<AppInfo>()
        for (entry in text.split(";")) {
            val item = entry.trim()
            if (item.isEmpty()) continue
            val parts = item.split("-", limit = 2)
            val raw = parts[0].trim().uppercase()
            // 设备把 id 当十六进制数发，前导零会被吃掉："5D69" 其实是 "00005D69"。
            // 所以只校验长度上限和字符合法性，然后左补零到 8 位。
            if (raw.isEmpty() || raw.length > ID_LENGTH ||
                raw.any { it !in "0123456789ABCDEF" }
            ) {
                SuitekiManager.log("app-list-skip", item)
                continue
            }
            apps.add(AppInfo(raw.padStart(ID_LENGTH, '0'), parts.getOrElse(1) { "" }.trim()))
        }
        list.clear()
        list.addAll(apps)
        SuitekiManager.log("app-list", "count=${apps.size}")
    }

    /**
     * 这里原来是 launch()，发 0x0023 + 0x07 + appId，想"启动手环上的应用"。
     *
     * 核对 Gadgetbridge 后确认这是错的：0x0023 是 ZeppOsWatchfaceService，
     * 0x07 是 CMD_SET（把某张表盘设为当前表盘），与"启动 app"无关。
     * Zepp OS 的 23 个服务里没有任何"远程启动应用"的端点，所以这个能力
     * 在协议层就不存在，真机上必然点了没反应。
     *
     * 更糟的是该服务 encrypt=true，Band 7 免认证状态下设备解不开这个包，
     * 即使想切表盘也只会被静默丢弃。
     *
     * 保留函数是为了让 AbstractSuitekiDevice 的接口不变，实际不发任何数据。
     */
    fun launch(appId: String) {
        SuitekiManager.log("launch-unsupported", appId)
    }

    fun uninstall(appId: String) {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(CMD_APPS)
            put(CMD_OUTGOING)
            put(CMD_APPS_DELETE)
            put(0x00)
            putInt(0x00)
            putInt(0x00)
            putInt(0x00)
            putInt(BytesUtils.hexToInt(appId))
        }
        device.write(0x00a0, buffer.array(), extendedFlags = true, encrypt = false)

        // 设备不一定回确认，本地先摘掉；indexOfFirst 找不到会返回 -1，直接 removeAt 会崩
        val index = list.indexOfFirst { it.id.equals(appId, ignoreCase = true) }
        if (index >= 0) list.removeAt(index)
    }

    fun requestAppItems() {
        SuitekiManager.log("requestApps")
        val payload = ByteArray(REQUEST_SIZE)
        payload[0] = CMD_APPS
        payload[1] = CMD_OUTGOING
        payload[2] = CMD_APPS_LIST
        payload[3] = 0x00
        device.write(0x00a0, payload, extendedFlags = true, encrypt = false)
    }
}
