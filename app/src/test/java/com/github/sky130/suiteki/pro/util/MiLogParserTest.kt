package com.github.sky130.suiteki.pro.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小米运动健康日志解析。
 *
 * 这段直接决定「通过日志添加设备」能不能拿到正确的密钥：
 * 华米系读 authKey，小米系读 encryptKey，读错就是连接时回 10 05 25。
 */
class MiLogParserTest {

    /** 模拟 Wearable.log 的一行，字段顺序与真机一致 */
    private fun line(
        mac: String,
        model: String,
        authKey: String = "",
        encryptKey: String = "",
    ) = "11-12 00:00:00.000 I/Wearable: device mac=$mac, model='$model', " +
        "authKey=$authKey, encryptKey=$encryptKey, extra=1"

    @Test
    fun `band7 取 authKey`() {
        val r = MiLogParser.parse(
            line("AA:BB:CC:DD:EE:FF", "hmpace.watch.v7", authKey = "0123456789ABCDEF", encryptKey = "IGNORED")
        )
        assertEquals(1, r.size)
        assertEquals("Xiaomi Smart Band 7 EEFF", r[0].name)
        assertEquals("band7", r[0].driverId)
        assertEquals("0123456789ABCDEF", r[0].key)
    }

    @Test
    fun `band8 取 encryptKey 而不是 authKey`() {
        val r = MiLogParser.parse(
            line("AA:BB:CC:DD:EE:01", "miwear.watch.m66nfc", authKey = "AAA", encryptKey = "BBB")
        )
        assertEquals("Xiaomi Smart Band 8 EE01", r[0].name)
        assertEquals("band8", r[0].driverId)
        assertEquals("BBB", r[0].key)
    }

    @Test
    fun `band8 pro 与 redmi watch 4 active 的名称后缀`() {
        val pro = MiLogParser.parse(
            line("AA:BB:CC:DD:EE:02", "lchz.watch.m67gl", encryptKey = "K")
        )[0]
        assertEquals("Xiaomi Smart Band 8 Pro EE02", pro.name)
        assertEquals("band8pro", pro.driverId)

        val active = MiLogParser.parse(
            line("AA:BB:CC:DD:EE:03", "lchz.watch.n65acn", encryptKey = "K")
        )[0]
        assertEquals("Redmi Watch 4 EE03 Active", active.name)
        assertEquals("redmiwatch4", active.driverId)
    }

    @Test
    fun `watch s3 的 model 能匹配（曾经多写了一个逗号）`() {
        val r = MiLogParser.parse(
            line("AA:BB:CC:DD:EE:04", "mijia.watch.n62", encryptKey = "K")
        )
        assertEquals(1, r.size)
        assertEquals("Xiaomi Watch S3 EE04 Active", r[0].name)
        assertEquals("watchs3", r[0].driverId)
    }

    @Test
    fun `同一 mac 多行只留一条，密钥取非空的那行`() {
        val text = listOf(
            line("AA:BB:CC:DD:EE:05", "hmpace.watch.v7", authKey = ""),
            line("AA:BB:CC:DD:EE:05", "hmpace.watch.v7", authKey = "KEY"),
        ).joinToString("\n")
        val r = MiLogParser.parse(text)
        assertEquals(1, r.size)
        assertEquals("KEY", r[0].key)
    }

    @Test
    fun `mac 大小写不同视为同一台设备`() {
        val text = listOf(
            line("aa:bb:cc:dd:ee:06", "hmpace.watch.v7", authKey = "K1"),
            line("AA:BB:CC:DD:EE:06", "hmpace.watch.v7", authKey = "K2"),
        ).joinToString("\n")
        assertEquals(1, MiLogParser.parse(text).size)
    }

    @Test
    fun `未知型号与残缺 mac 直接忽略`() {
        val text = listOf(
            line("AA:BB:CC:DD:EE:07", "some.unknown.model", authKey = "K"),
            "garbage mac=AA:BB:CC, model='hmpace.watch.v7', authKey=K",
            "no mac here",
        ).joinToString("\n")
        assertTrue(MiLogParser.parse(text).isEmpty())
    }

    @Test
    fun `空文本与空行不抛异常`() {
        assertTrue(MiLogParser.parse("").isEmpty())
        assertTrue(MiLogParser.parse("\n\n\n").isEmpty())
    }

    @Test
    fun `between 取不到边界时返回空串`() {
        assertEquals("", MiLogParser.between("abc", "x", "y"))
        assertEquals("", MiLogParser.between("mac=AA, ", "mac=", "}, "))
        assertEquals("AA", MiLogParser.between("mac=AA, ", "mac=", ", "))
    }
}
