package com.github.sky130.suiteki.pro.logic.ble

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.github.sky130.suiteki.pro.MainApplication.Companion.context
import com.github.sky130.suiteki.pro.logic.database.model.Device
import com.github.sky130.suiteki.pro.util.BytesUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 一个设备驱动。由 [Suiteki] 注解在启动时注册，id 是稳定键。
 */
data class DeviceDriver(
    val id: String,
    val displayName: String,
    val needsKey: Boolean,
    val hints: List<String>,
    val clazz: Class<*>,
)

object SuitekiManager {
    private val drivers = LinkedHashMap<String, DeviceDriver>()
    private val flow = MutableStateFlow<AbstractSuitekiDevice?>(null)
    val bleDevice: StateFlow<AbstractSuitekiDevice?> get() = flow
    val logList = mutableListOf<String>()
    val installStatus = mutableStateOf<InstallStatus>(InstallStatus.Nope)

    /** 连接失败原因。匹配不到驱动时不再静默返回，UI 要能看见。 */
    val connectError = MutableStateFlow<String?>(null)

    fun log(vararg str: Any) {
        logList.add(str.joinToString("\n") {
            if (it is ByteArray) {
                BytesUtils.bytesToHexStr(it).toString()
            } else {
                it.toString()
            }
        })
    }

    fun waitForAuth() = if (bleDevice.value?.status?.value == DeviceStatus.Connected) {
        Unit
    } else {
        null
    }

    fun driverList(): List<DeviceDriver> = drivers.values.toList()

    fun driver(id: String): DeviceDriver? = drivers[id]

    /**
     * 用蓝牙广播名推荐一个型号，供扫描列表预选。只做推荐，用户确认后才写入设备。
     * 广播名可能为 null（手环没被任何 App 连过时很常见），此时返回 null，让用户自己选。
     */
    fun recommend(bluetoothName: String?): DeviceDriver? {
        val n = bluetoothName?.trim().orEmpty()
        if (n.isEmpty()) return null
        // 取最长命中的 hint：否则 "Xiaomi Smart Band 8" 会先吃掉 "8 Pro"
        var best: DeviceDriver? = null
        var bestLen = 0
        for (d in drivers.values) {
            for (h in d.hints) {
                if (h.length > bestLen && n.startsWith(h, ignoreCase = true)) {
                    best = d
                    bestLen = h.length
                }
            }
        }
        return best
    }

    fun connect(device: Device) {
        connectError.value = null
        // 优先按库里存的 driverId；老数据没有这一列时，用广播名/备注前缀兜底一次
        val driver = drivers[device.driverId]
            ?: drivers.values.firstOrNull { d ->
                d.hints.any { device.name.startsWith(it, ignoreCase = true) }
            }
        if (driver == null) {
            connectError.value = "无法识别设备类型（${device.name}），请编辑设备并选择型号"
            return
        }
        val instance = runCatching {
            driver.clazz.getConstructor(
                String::class.java,
                String::class.java,
                String::class.java,
            ).newInstance(device.name, device.mac, device.key)
        }.getOrElse {
            connectError.value = "创建 ${driver.displayName} 驱动失败：${it.message}"
            return
        }
        @Suppress("UNCHECKED_CAST")
        flow.value = (instance as AbstractSuitekiDevice).apply { onStart() }
    }

    fun init() {
        drivers.clear()
        ClassesReader.reader("com.github.sky130.suiteki.pro", context)
            .filter { !it.name.contains("$") }.forEach { clazz ->
                clazz.annotations.filterIsInstance<Suiteki>().forEach { ann ->
                    drivers[ann.id] = DeviceDriver(
                        id = ann.id,
                        displayName = ann.displayName,
                        needsKey = ann.needsKey,
                        hints = ann.hints.toList(),
                        clazz = clazz,
                    )
                    Log.d("TAG", "driver: ${ann.id} -> ${clazz.name}")
                }
            }
    }
}
