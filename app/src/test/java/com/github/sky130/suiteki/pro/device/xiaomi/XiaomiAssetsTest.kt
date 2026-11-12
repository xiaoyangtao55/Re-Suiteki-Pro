package com.github.sky130.suiteki.pro.device.xiaomi

import com.github.sky130.suiteki.pro.proto.xiaomi.XiaomiProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备信息（2/2）与表盘列表（4/0）的字段映射。
 *
 * 这两块是纯解析，设备回什么就映射什么，不改写设备给的值，
 * 所以单测直接构造 proto 就能覆盖——不需要真机。
 */
class XiaomiAssetsTest {

    @Test
    fun `设备信息按字段映射到 HardwareInfo`() {
        val info = XiaomiProto.DeviceInfo.newBuilder()
            .setSerialNumber("3C:1A:9F:00:11:22")
            .setFirmware("1.2.3.4")
            .setModel("Xiaomi Smart Band 8")
            .build()

        val hw = XiaomiSystemHelper.parse(info)

        assertEquals("1.2.3.4", hw.firmware)
        assertEquals("Xiaomi Smart Band 8", hw.model)
        assertEquals("3C:1A:9F:00:11:22", hw.serial)
        assertFalse(hw.isEmpty)
    }

    @Test
    fun `设备信息前后空格会被裁掉`() {
        val info = XiaomiProto.DeviceInfo.newBuilder()
            .setSerialNumber("  SN123  ")
            .setFirmware(" 2.0.0 ")
            .setModel(" Watch S3 ")
            .build()

        val hw = XiaomiSystemHelper.parse(info)

        assertEquals("2.0.0", hw.firmware)
        assertEquals("Watch S3", hw.model)
        assertEquals("SN123", hw.serial)
    }

    @Test
    fun `设备三个字段都为空时视为没读到`() {
        val hw = XiaomiSystemHelper.parse(
            XiaomiProto.DeviceInfo.newBuilder()
                .setSerialNumber("")
                .setFirmware("")
                .setModel("")
                .build()
        )
        assertTrue(hw.isEmpty)
    }

    @Test
    fun `表盘列表保留设备给的 active 与 canDelete`() {
        val list = XiaomiProto.WatchfaceList.newBuilder()
            .addWatchface(
                XiaomiProto.WatchfaceInfo.newBuilder()
                    .setId("100").setName("默认").setActive(true).setCanDelete(false)
            )
            .addWatchface(
                XiaomiProto.WatchfaceInfo.newBuilder()
                    .setId("200").setName("赛博").setActive(false).setCanDelete(true)
            )
            .build()

        val parsed = XiaomiWatchfaceHelper.parse(list)

        assertEquals(2, parsed.size)
        assertTrue(parsed[0].active)
        assertFalse(parsed[0].canDelete)
        assertFalse(parsed[1].active)
        assertTrue(parsed[1].canDelete)
    }

    @Test
    fun `表盘没有名字时用 id 兜底`() {
        val list = XiaomiProto.WatchfaceList.newBuilder()
            .addWatchface(
                XiaomiProto.WatchfaceInfo.newBuilder().setId("9527").setName("").setCanDelete(true)
            )
            .build()

        val parsed = XiaomiWatchfaceHelper.parse(list)

        assertEquals("9527", parsed.single().name)
    }

    @Test
    fun `表盘名前后空格会被裁掉`() {
        val list = XiaomiProto.WatchfaceList.newBuilder()
            .addWatchface(
                XiaomiProto.WatchfaceInfo.newBuilder().setId("7").setName("  极简  ")
            )
            .build()

        assertEquals("极简", XiaomiWatchfaceHelper.parse(list).single().name)
    }
}
