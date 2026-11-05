package com.github.sky130.suiteki.pro.logic.ble

import com.github.sky130.suiteki.pro.device.huami.HuamiService

/**
 * 判断选中的文件是表盘还是小程序。
 *
 * 米环 7 的表盘是裸 .bin；小程序是 .zab（zip 容器，内含 app.json）。
 * D2 头里的类型字节写错，设备会按表盘去解析 zip，回 10D656「不支持的文件」。
 */
object InstallType {

    fun detect(fileName: String, bytes: ByteArray): Byte {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".zab") || lower.endsWith(".zip") -> HuamiService.TYPE_D2_APP
            lower.endsWith(".bin") -> HuamiService.TYPE_D2_WATCHFACE
            // 没有扩展名时退回魔数：PK\x03\x04 当作小程序包
            isZip(bytes) -> HuamiService.TYPE_D2_APP
            else -> HuamiService.TYPE_D2_WATCHFACE
        }
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size > 4 &&
                bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
                bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
}
