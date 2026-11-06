package com.github.sky130.suiteki.pro.device.huami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0x00A0 应用列表的解析。真机上一共 18 条，其中有 4 条的 id 首位是 8 以上、
 * 还有几条带前导零，这两类都出过问题，所以单独覆盖。
 */
class HuamiAppListParseTest {

    private fun payload(text: String): ByteArray {
        val head = ByteArray(16)
        head[0] = 0x02
        head[1] = 0x00
        head[2] = 0x01
        return head + text.toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `解析 id 与版本`() {
        val (apps, skipped) = HuamiAppListHelper.parseListPayload(
            payload("0A1B2C3D-1.0.0;0A1B2C3E-2.1.3;")
        )
        assertEquals(2, apps.size)
        assertEquals("0A1B2C3D", apps[0].id)
        assertEquals("1.0.0", apps[0].name)
        assertEquals("0A1B2C3E", apps[1].id)
        assertEquals("2.1.3", apps[1].name)
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun `被吃掉的前导零补齐到八位`() {
        val (apps, _) = HuamiAppListHelper.parseListPayload(payload("5D69-1.0.0;F43D9-2.0.0;"))
        assertEquals("00005D69", apps[0].id)
        assertEquals("000F43D9", apps[1].id)
    }

    @Test
    fun `小写十六进制统一成大写`() {
        val (apps, _) = HuamiAppListHelper.parseListPayload(payload("0a1b2c3d-1.0.0"))
        assertEquals("0A1B2C3D", apps[0].id)
    }

    @Test
    fun `非十六进制条目被跳过而不是整包丢弃`() {
        val (apps, skipped) = HuamiAppListHelper.parseListPayload(
            payload("0A1B2C3D-1.0.0;ZZZZZZZZ-9.9.9;0A1B2C3E-2.1.3")
        )
        assertEquals(2, apps.size)
        assertEquals(1, skipped.size)
        assertEquals("ZZZZZZZZ-9.9.9", skipped[0])
    }

    @Test
    fun `超过八位的 id 被跳过`() {
        val (apps, skipped) = HuamiAppListHelper.parseListPayload(payload("0A1B2C3D4-1.0.0"))
        assertTrue(apps.isEmpty())
        assertEquals(1, skipped.size)
    }

    @Test
    fun `末尾的分号与空条目不会产生空应用`() {
        val (apps, _) = HuamiAppListHelper.parseListPayload(payload("0A1B2C3D-1.0.0;;;"))
        assertEquals(1, apps.size)
    }

    @Test
    fun `结尾的零字节会被裁掉`() {
        val (apps, _) = HuamiAppListHelper.parseListPayload(payload("0A1B2C3D-1.0.0\u0000\u0000"))
        assertEquals(1, apps.size)
        assertEquals("1.0.0", apps[0].name)
    }

    @Test
    fun `没有版本段时名字为空`() {
        val (apps, _) = HuamiAppListHelper.parseListPayload(payload("0A1B2C3D"))
        assertEquals(1, apps.size)
        assertEquals("", apps[0].name)
    }

    @Test
    fun `只有包头时返回空列表`() {
        val (apps, skipped) = HuamiAppListHelper.parseListPayload(payload(""))
        assertTrue(apps.isEmpty())
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun `短于包头的输入不越界`() {
        val (apps, skipped) = HuamiAppListHelper.parseListPayload(ByteArray(8))
        assertTrue(apps.isEmpty())
        assertTrue(skipped.isEmpty())
    }
}
