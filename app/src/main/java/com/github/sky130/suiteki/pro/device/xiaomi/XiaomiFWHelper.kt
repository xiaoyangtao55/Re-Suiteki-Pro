/*  Copyright (C) 2023-2024 José Rebelo

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package com.github.sky130.suiteki.pro.device.xiaomi

import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.CMD_FIRMWARE_INSTALL
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.CMD_RPK_INSTALL
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.CMD_WATCHFACE_INSTALL
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.RPK_COMMAND_TYPE
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.SYSTEM_COMMAND_TYPE
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.TYPE_FIRMWARE
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.TYPE_RPK
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.TYPE_UNKNOWN
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.TYPE_WATCHFACE
import com.github.sky130.suiteki.pro.device.xiaomi.XiaomiService.WATCHFACE_COMMAND_TYPE
import com.github.sky130.suiteki.pro.util.BytesUtils
import com.github.sky130.suiteki.pro.util.StringUtils.untilNullTerminator
import com.github.sky130.suiteki.pro.util.hashBytes
import com.github.sky130.suiteki.pro.util.toHex
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream


class XiaomiFWHelper(var bytes: ByteArray?) {
    var subType = -1
        private set

    /**
     * 初值必须是 TYPE_UNKNOWN。原来写 -1，而 [XiaomiInstallHelper.install] 里拦的是
     * `fileType == TYPE_UNKNOWN`（0），条件永远不成立，空文件照样会发一条 type=-1 的命令。
     */
    var fileType = TYPE_UNKNOWN
        private set
    var type = -1
        private set
    var id: String? = null
        private set
    var packageName: String? = null
        private set
    var name: String? = null
        private set
    var versionName: String? = null
        private set
    var versionCode: Int? = null
        private set
    var md5: String? = null
        private set


    fun init() {
        parseBytes()
    }

    val details: String
        get() = if (name != null) name!! else (versionName ?: "UNKNOWN")

    fun unsetFwBytes() {
        this.bytes = null
    }

    private fun parseBytes() {
        val data = bytes
        // 空文件 / 几字节的垃圾文件以前会在下面 bytes[0] 处直接越界崩溃，
        // 而且崩在 install() 的协程里会带到主线程。先挡掉。
        if (data == null || data.size < 4) {
            fileType = TYPE_UNKNOWN
            return
        }
        // 解析过程里还有若干按固定 offset 取字段的地方（固件头、表盘头），
        // 长度不够时会抛 IndexOutOfBounds。这里统一收成"认不出"，不要崩到 UI。
        runCatching {
            if (parseAsWatchface()) {
                checkNotNull(id)
                subType = CMD_WATCHFACE_INSTALL
                type = WATCHFACE_COMMAND_TYPE
                fileType = TYPE_WATCHFACE
            } else if (parseAsFirmware()) {
                checkNotNull(versionName)
                fileType = TYPE_FIRMWARE
                type = SYSTEM_COMMAND_TYPE
                subType = CMD_FIRMWARE_INSTALL
            } else if (parseAsRpk()) {
                subType = CMD_RPK_INSTALL
                type = RPK_COMMAND_TYPE
                fileType = TYPE_RPK
            } else {
                fileType = TYPE_UNKNOWN
            }
        }.onFailure {
            fileType = TYPE_UNKNOWN
            subType = -1
            type = -1
        }
    }

    private fun parseAsWatchface(): Boolean {
        val bytes = this.bytes!!
        if (bytes[0] == 90.toByte() && bytes[1] == (-91).toByte()) {
            id = untilNullTerminator(bytes, 40)
            val name = untilNullTerminator(bytes, 104)
            if (id != null && name != null) {
                return try {
                    id!!.toInt()
                    true
                } catch (e: NumberFormatException) {
                    false
                }
            }
        }
        return false
    }

    private fun parseAsFirmware(): Boolean {
        val data = this.bytes ?: return false
        // 下面要读到第 11 字节（版本号），4~10 字节的文件会直接越界
        if (data.size < 11) return false
        val start = listOf<Byte>(96, 90, 90, 126)
        for ((i, b) in start.withIndex()) {
            if (data[i] != b) return false
        }
        versionName = data.decodeToString(4, 11)
        md5 = hashBytes(data, "MD5").toHex()
        return true
    }

    /**
     * 原来是 `File.createTempFile` + ZipFile：Android 上 java.io.tmpdir 是 /data/local/tmp，
     * 普通应用没有写权限，createTempFile 必然抛 IOException，被 catch 掉之后一律返回 false
     * ——也就是说小程序（rpk）在这台机器上从来没被识别成功过。改成直接解内存里的 zip。
     */
    private fun parseAsRpk(): Boolean {
        val data = this.bytes ?: return false
        val manifest = runCatching {
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                generateSequence { zip.nextEntry }
                    .firstOrNull { !it.isDirectory && it.name == "manifest.json" }
                    ?.let { zip.readBytes().toString(Charsets.UTF_8) }
            }
        }.getOrNull() ?: return false

        return runCatching {
            JSONObject(manifest).apply {
                packageName = getString("package").isEmptyThrow()
                name = getString("name").isEmptyThrow()
                versionName = getString("versionName").isEmptyThrow()
                versionCode = getInt("versionCode")
                id = packageName
            }
        }.isSuccess
    }

    private fun String.isEmptyThrow(e: Exception = Exception()) = apply {
        ifEmpty { throw e }
    }
}