package com.github.sky130.suiteki.pro.device.huami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装后靠列表差集认 id。
 *
 * 表盘 `.bin` 里没有 appId，只能拿安装前后的 0x00A0 列表做差。
 * 差集为空、或有多个新增时都不能乱配名字。
 */
class HuamiInstallRecordTest {

    @Test
    fun `只返回安装后新增的 id`() {
        val before = setOf("00000001", "00000002")
        val now = listOf("00000001", "00000002", "00000003")

        assertEquals(listOf("00000003"), HuamiDevice.addedIds(before, now))
    }

    @Test
    fun `没有新增时返回空`() {
        val before = setOf("00000001", "00000002")
        val now = listOf("00000001", "00000002")

        assertTrue(HuamiDevice.addedIds(before, now).isEmpty())
    }

    @Test
    fun `安装前没有快照时不猜`() {
        // 空集合意味着「没拉到过列表」，那时候任何 id 都可能是新的，不能配名字
        assertTrue(HuamiDevice.addedIds(emptySet(), listOf("0000000A", "0000000B")).isEmpty())
    }

    @Test
    fun `多个新增时按设备返回顺序`() {
        val before = setOf("00000001")
        val now = listOf("00000001", "0000000B", "0000000C")

        assertEquals(listOf("0000000B", "0000000C"), HuamiDevice.addedIds(before, now))
    }
}
