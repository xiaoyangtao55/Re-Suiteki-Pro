package com.github.sky130.suiteki.pro.device.huami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 电量：标准电池服务 0x180F / 2A19，首字节是百分比 */
class HuamiBatteryTest {

    @Test
    fun `首字节是百分比`() {
        assertEquals(85, HuamiBleSupport.parseBatteryLevel(byteArrayOf(0x55)))
    }

    @Test
    fun `0 和 100 都是合法值`() {
        assertEquals(0, HuamiBleSupport.parseBatteryLevel(byteArrayOf(0x00)))
        assertEquals(100, HuamiBleSupport.parseBatteryLevel(byteArrayOf(0x64)))
    }

    @Test
    fun `空数据视为没读到`() {
        assertNull(HuamiBleSupport.parseBatteryLevel(null))
        assertNull(HuamiBleSupport.parseBatteryLevel(byteArrayOf()))
    }

    @Test
    fun `大于 100 的值丢弃，不当成电量`() {
        // 0xFF 无符号解释是 255，典型的是「特征不可读」而不是真实电量
        assertNull(HuamiBleSupport.parseBatteryLevel(byteArrayOf(0xFF.toByte())))
        assertNull(HuamiBleSupport.parseBatteryLevel(byteArrayOf(0x65)))
    }

    @Test
    fun `多余字节忽略，只看第一个`() {
        assertEquals(42, HuamiBleSupport.parseBatteryLevel(byteArrayOf(42, 1)))
    }
}
