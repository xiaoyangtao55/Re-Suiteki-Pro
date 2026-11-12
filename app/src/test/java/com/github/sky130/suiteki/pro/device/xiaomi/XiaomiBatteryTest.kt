package com.github.sky130.suiteki.pro.device.xiaomi

import com.github.sky130.suiteki.pro.proto.xiaomi.XiaomiProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电量解析：2/1（Battery）与 2/78（BasicDeviceState）。
 *
 * 关键是两个来源字段不齐时不能互相冲掉：
 * 2/78 可能没有 batteryLevel，2/1 可能没有充电状态。
 */
class XiaomiBatteryTest {

    private fun battery(level: Int, state: Int? = null): XiaomiProto.Battery =
        XiaomiProto.Battery.newBuilder().apply {
            setLevel(level)
            if (state != null) setState(state)
        }.build()

    @Test
    fun `2-1 充电中`() {
        val info = XiaomiSystemHelper.parseBattery(battery(85, 1))!!
        assertEquals(85, info.level)
        assertTrue(info.charging == true)
    }

    @Test
    fun `2-1 未充电的两种原始值`() {
        assertTrue(XiaomiSystemHelper.parseBattery(battery(60, 2))!!.charging == false)
        assertTrue(XiaomiSystemHelper.parseBattery(battery(60, 3))!!.charging == false)
    }

    @Test
    fun `2-1 没带充电状态时留空，不猜成未充电`() {
        val info = XiaomiSystemHelper.parseBattery(battery(50))!!
        assertEquals(50, info.level)
        assertNull(info.charging)
    }

    @Test
    fun `2-1 未知充电状态不猜`() {
        assertNull(XiaomiSystemHelper.parseBattery(battery(50, 9))!!.charging)
    }

    @Test
    fun `2-1 没有百分比时当成没读到`() {
        val empty = XiaomiProto.Battery.newBuilder().setState(1).build()
        assertNull(XiaomiSystemHelper.parseBattery(empty))
    }

    @Test
    fun `2-1 百分比越界直接丢弃`() {
        assertNull(XiaomiSystemHelper.parseBattery(battery(101)))
        assertNull(XiaomiSystemHelper.parseBattery(battery(150)))
    }

    @Test
    fun `2-78 同时给出百分比和充电状态`() {
        val state = XiaomiProto.BasicDeviceState.newBuilder()
            .setBatteryLevel(33)
            .setIsCharging(false)
            .setIsWorn(true)
            .setIsUserAsleep(false)
            .build()

        val info = XiaomiSystemHelper.parseBasicState(state)!!
        assertEquals(33, info.level)
        assertTrue(info.charging == false)
    }

    @Test
    fun `2-78 没带百分比时交给 2-1 去问`() {
        val state = XiaomiProto.BasicDeviceState.newBuilder()
            .setIsCharging(true)
            .setIsWorn(true)
            .setIsUserAsleep(false)
            .build()

        assertNull(XiaomiSystemHelper.parseBasicState(state))
    }

    @Test
    fun `20 及以下算电量偏低`() {
        assertTrue(com.github.sky130.suiteki.pro.logic.ble.BatteryInfo(20).isLow)
        assertTrue(com.github.sky130.suiteki.pro.logic.ble.BatteryInfo(5).isLow)
        assertFalse(com.github.sky130.suiteki.pro.logic.ble.BatteryInfo(21).isLow)
    }
}
