package com.github.sky130.suiteki.pro.logic.ble

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.github.sky130.suiteki.pro.MainApplication.Companion.context
import com.github.sky130.suiteki.pro.device.huami.miband7.Miband7
import com.github.sky130.suiteki.pro.device.xiaomi.miband8.MiBand8
import com.github.sky130.suiteki.pro.device.xiaomi.miband8pro.MiBand8Pro
import com.github.sky130.suiteki.pro.device.xiaomi.redmiwatch4.RedmiWatch4
import com.github.sky130.suiteki.pro.device.xiaomi.watchs3.WatchS3
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

    /** dex 扫描失败时的兜底注册表。注解仍然是唯一事实来源，这里只列类。 */
    private val FALLBACK_DRIVERS = listOf(
        Miband7::class.java,
        MiBand8::class.java,
        MiBand8Pro::class.java,
        RedmiWatch4::class.java,
        WatchS3::class.java,
    )
    private val flow = MutableStateFlow<AbstractSuitekiDevice?>(null)
    val bleDevice: StateFlow<AbstractSuitekiDevice?> get() = flow
    /**
     * 调试日志。用 SnapshotStateList 而不是普通 MutableList，
     * 否则 Compose 里 remember 一次之后新日志不会触发重组，对话框必须关掉重开。
     * 同时留上限：原来是无界列表，连着跑一天会把握手 hex 全堆在内存里。
     *
     * 写入一律切回主线程：log() 的调用点散布在 BLE notify 回调、SPP 读线程和协程里，
     * 直接从后台线程改 SnapshotStateList 会和 Compose 组合期的遍历撞上（并发修改）。
     */
    val logList = mutableStateListOf<String>()
    private const val MAX_LOG_LINES = 500
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    val installStatus = mutableStateOf<InstallStatus>(InstallStatus.Nope)

    /** 连接失败原因。匹配不到驱动时不再静默返回，UI 要能看见。 */
    val connectError = MutableStateFlow<String?>(null)

    fun log(vararg str: Any) {
        val line = str.joinToString("\n") {
            if (it is ByteArray) {
                BytesUtils.bytesToHexStr(it).toString()
            } else {
                it.toString()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appendLog(line)
        } else {
            mainHandler.post { appendLog(line) }
        }
    }

    private fun appendLog(line: String) {
        logList.add(line)
        while (logList.size > MAX_LOG_LINES) {
            logList.removeAt(0)
        }
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
        // 先收掉上一条连接。以前这里直接覆盖 flow，旧实例连同它的 GATT / SPP socket
        // 一起被丢弃：socket 不关会一直占着 RFCOMM，重连后设备只认旧的那条。
        flow.value?.let { old ->
            runCatching { old.disconnect() }
        }
        flow.value = null
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
        // dex 扫描失败不该让整个 App 变成"什么型号都选不了"，所以整段包在 runCatching 里
        val scanned = runCatching {
            ClassesReader.reader("com.github.sky130.suiteki.pro", context).toList()
        }.getOrDefault(emptyList())
        scanned.filter { !it.name.contains("$") }.forEach { clazz ->
            clazz.annotations.filterIsInstance<Suiteki>().forEach { ann ->
                register(ann, clazz)
            }
        }
        if (drivers.isEmpty()) {
            // 兜底：某些 ROM / 分包 / R8 配置下 DexFile 扫不到业务类，
            // 这时显式注册已知的五个驱动，至少保证型号列表不是空的。
            Log.w("TAG", "dex scan returned nothing, falling back to explicit drivers")
            FALLBACK_DRIVERS.forEach { clazz ->
                clazz.getAnnotation(Suiteki::class.java)?.let { register(it, clazz) }
            }
        }
    }

    private fun register(ann: Suiteki, clazz: Class<*>) {
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
