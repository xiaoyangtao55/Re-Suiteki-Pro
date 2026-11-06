package com.github.sky130.suiteki.pro.logic.ble

import com.github.sky130.suiteki.pro.device.huami.HuamiService
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D2 头里的类型字节写错，设备会把 zip 当表盘解析，回 10D656。
 * 这里覆盖扩展名判定和魔数兜底两条路径。
 */
class InstallTypeTest {

    private val zipMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)

    @Test
    fun `zab 与 zip 判为小程序`() {
        assertEquals(HuamiService.TYPE_D2_APP, InstallType.detect("a.zab", ByteArray(16)))
        assertEquals(HuamiService.TYPE_D2_APP, InstallType.detect("A.ZIP", ByteArray(16)))
    }

    @Test
    fun `bin 判为表盘`() {
        assertEquals(HuamiService.TYPE_D2_WATCHFACE, InstallType.detect("face.bin", zipMagic))
    }

    @Test
    fun `没有扩展名时按 PK 魔数兜底`() {
        assertEquals(HuamiService.TYPE_D2_APP, InstallType.detect("download", zipMagic))
    }

    @Test
    fun `既不认识扩展名也没有魔数时按表盘处理`() {
        assertEquals(
            HuamiService.TYPE_D2_WATCHFACE,
            InstallType.detect("download", ByteArray(16))
        )
    }

    @Test
    fun `后缀大小写不敏感`() {
        assertEquals(HuamiService.TYPE_D2_APP, InstallType.detect("x.ZaB", ByteArray(16)))
        assertEquals(HuamiService.TYPE_D2_WATCHFACE, InstallType.detect("x.BIN", ByteArray(16)))
    }
}
