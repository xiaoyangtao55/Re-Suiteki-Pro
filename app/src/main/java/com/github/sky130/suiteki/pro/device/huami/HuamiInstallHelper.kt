package com.github.sky130.suiteki.pro.device.huami

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.clj.fastble.BleManager
import com.github.sky130.suiteki.pro.logic.ble.InstallStatus
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.util.BytesUtils

/**
 * 安装状态机：D0 → D1 → D2(14B 头) → D3 01 → 每 8KB 等一次 10D4 → D5 → D6
 *
 * @param type D2 头里的类型字节：0x08 表盘 / 0xA0 小程序 / 0xFD 固件。
 *             写死成表盘的话，小程序会被设备当成表盘解析，回 10D656。
 * @param mtu  链路协商到的 MTU，用来算 FastBle 的分片大小；必须是 mtu-3，
 *             否则一次写超过 MTU 会直接被协议栈拒绝。
 */
class HuamiInstallHelper(
    val device: HuamiDevice,
    val bytes: ByteArray,
    private val type: Byte = HuamiService.TYPE_D2_WATCHFACE,
    private val mtu: Int = 23,
) {
    companion object {
        val d0 = byteArrayOf(-48)
        val d1 = byteArrayOf(-47)
        val d3 = byteArrayOf(-45, 1)
        val d5 = byteArrayOf(-43)
        val d6 = byteArrayOf(-42)

        /** 单包上限，与设备侧一致 */
        private const val MAX_CHUNK = 244
        /** 每一步（控制帧应答 / 一个 8KB 块）的超时 */
        private const val STEP_TIMEOUT_MS = 15_000L
        /** 认证失效后允许重试的次数 */
        private const val MAX_AUTH_RETRY = 1
    }

    lateinit var d2: ByteArray
    private lateinit var splitBytes: ArrayList<ByteArray>
    private val performList by lazy { listOf(d0, d1, d2, d3, d5, d6) }

    private val handler = Handler(Looper.getMainLooper())
    private var performCount = 0
    private var installCount = 0
    private var authRetry = 0
    private var finished = false
    private val progress get() = ((installCount.toFloat() / splitBytes.size.toFloat()) * 100).toInt()

    /** 15 秒没动静就判失败，不再无限期挂着 */
    private val watchdog = Runnable {
        if (!finished) {
            installFailure("设备无响应，请确认手环未与官方 App 连接后重试")
        }
    }

    fun start() {
        SuitekiManager.installStatus.value = InstallStatus.Nope
        d2 = BytesUtils.getD2(bytes, type)
        splitBytes = BytesUtils.splitBytes(bytes)
        // 关键：244 只有在 MTU >= 247 时才成立，按实际 MTU 收敛
        BleManager.getInstance().splitWriteNum = minOf(MAX_CHUNK, maxOf(20, mtu - 3))
        doPerform()
    }

    private fun doPerform() {
        if (finished) return
        if (performCount >= performList.size) {
            installFailure("协议状态异常（控制帧超出预期）")
            return
        }
        write(performList[performCount])
        performCount++
        kick()
    }

    private fun install() {
        if (finished) return
        if (installCount >= splitBytes.size) {
            doPerform()
            return
        }
        write(splitBytes[installCount], notify = false)
        installCount++
        updateProgress()
        kick()
    }

    private fun write(data: ByteArray, notify: Boolean = true) {
        device.write(
            data,
            HuamiService.UUID_SERVICE_FIRMWARE,
            if (notify) HuamiService.UUID_CHARACTERISTIC_FIRMWARE_NOTIFY
            else HuamiService.UUID_CHARACTERISTIC_FIRMWARE_WRITE
        )
        Log.d("TAG", "< ${BytesUtils.bytesToHexStr(data).toString()}")
    }

    private fun updateProgress() {
        SuitekiManager.installStatus.value = InstallStatus.Installing(progress)
    }

    private fun installFailure(messages: String) {
        if (finished) return
        finished = true
        handler.removeCallbacks(watchdog)
        restoreSplit()
        SuitekiManager.installStatus.value = InstallStatus.InstallFailure(progress, messages)
    }

    private fun installSuccess() {
        if (finished) return
        finished = true
        handler.removeCallbacks(watchdog)
        restoreSplit()
        SuitekiManager.installStatus.value = InstallStatus.InstallSuccess(100)
        device.installFinish()
    }

    private fun kick() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, STEP_TIMEOUT_MS)
    }

    fun cancel() {
        finished = true
        handler.removeCallbacks(watchdog)
        restoreSplit()
    }

    /** splitWriteNum 是 FastBle 的全局静态量，安装结束后必须还原，否则会带坏小米那条链路 */
    private fun restoreSplit() {
        BleManager.getInstance().splitWriteNum = 20
    }

    fun handleBytes(bytes: ByteArray) {
        if (finished) return
        Log.d("TAG", "> ${BytesUtils.bytesToHexStr(bytes).toString()}")
        kick()
        when (BytesUtils.bytesToHexStr(bytes)) {
            "10D001050020", "10D00105002001", "10D10100", "10D2010000000000000000" -> doPerform()

            "10D203" -> {
                // 认证失效。1.5.1 的做法是 reAuth 后从 D0 重来，这里照做
                if (authRetry >= MAX_AUTH_RETRY) {
                    installFailure("验证失败：设备认证已失效，请重新连接手环")
                    return
                }
                authRetry++
                performCount = 0
                device.auth {
                    if (!finished) {
                        performCount = 0
                        doPerform()
                    }
                }
                return
            }

            "10D347" -> {
                installFailure("空间不足")
                return
            }

            "10D656" -> {
                installFailure("不支持的文件（包类型可能不对）")
                return
            }

            "10D301" -> {
                install()
                return
            }

            "10D501" -> {
                doPerform()
                return
            }

            "10D601" -> {
                installSuccess()
                return
            }

            else -> {
                bytes.let {
                    if (it.size >= 2 && it[0].toInt() == 16 && it[1].toInt() == -44) { // 10D4
                        return if (installCount >= splitBytes.size) doPerform() else install()
                    }
                    if (it.size >= 3 && it[0].toInt() == 16 && it[1].toInt() == -46 && it[2].toInt() == 1) {
                        return doPerform()
                    }
                }
                installFailure("未知原因\n${BytesUtils.bytesToHexStr(bytes)}")
                return
            }
        }
    }
}
