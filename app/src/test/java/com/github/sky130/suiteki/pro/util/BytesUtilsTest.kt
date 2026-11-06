package com.github.sky130.suiteki.pro.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.zip.CRC32

/**
 * 协议里最容易出错、又最适合脱离设备验证的部分：密钥回落、CRC/长度编码、分包。
 */
class BytesUtilsTest {

    private val defaultKey = "0123456789@ABCDE"

    @Test
    fun `空密钥回落默认授权密钥`() {
        val key = BytesUtils.getSecretKey("")
        assertEquals(defaultKey, key.toString(Charsets.US_ASCII))
    }

    @Test
    fun `空白密钥回落默认授权密钥`() {
        assertEquals(defaultKey, BytesUtils.getSecretKey("   ").toString(Charsets.US_ASCII))
    }

    @Test
    fun `长度不足的十六进制回落默认密钥`() {
        assertEquals(defaultKey, BytesUtils.getSecretKey("0x1234").toString(Charsets.US_ASCII))
    }

    @Test
    fun `含非十六进制字符的密钥回落默认密钥`() {
        assertEquals(
            defaultKey,
            BytesUtils.getSecretKey("zz0102030405060708090a0b0c0d0e0f").toString(Charsets.US_ASCII)
        )
    }

    @Test
    fun `合法 32 位十六进制按字节解析`() {
        val key = BytesUtils.getSecretKey("000102030405060708090A0B0C0D0E0F")
        assertEquals(16, key.size)
        assertEquals(0x00.toByte(), key[0])
        assertEquals(0x0F.toByte(), key[15])
    }

    @Test
    fun `0x 前缀会被剥掉`() {
        assertArrayEquals(
            BytesUtils.getSecretKey("000102030405060708090A0B0C0D0E0F"),
            BytesUtils.getSecretKey("0x000102030405060708090A0B0C0D0E0F")
        )
    }

    /**
     * 8 位 id 的首位 >= 8 时（0xF1A2B3C4）直接用 toInt(16) 会抛 NumberFormatException，
     * 之前 18 个应用里有 4 个因为这个被整条丢掉。
     */
    @Test
    fun `八位十六进制按位模式截断为 Int`() {
        assertEquals(0xF1A2B3C4L.toInt(), BytesUtils.hexToInt("F1A2B3C4"))
        assertEquals(0x00005D69, BytesUtils.hexToInt("5D69"))
        assertEquals(0, BytesUtils.hexToInt("00000000"))
    }

    @Test
    fun `十六进制字符串与字节数组互转`() {
        val bytes = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
        assertEquals("007F80FF", BytesUtils.bytesToHexStr(bytes))
        assertArrayEquals(bytes, BytesUtils.hexToBytes("007F80FF"))
    }

    @Test
    fun `D2 头里的长度与 CRC 是小端序`() {
        val data = ByteArray(300) { 0x11 }
        val d2 = BytesUtils.getD2(data, 0x08)

        assertEquals(14, d2.size)
        assertEquals(0xD2.toByte(), d2[0])
        assertEquals(0x08.toByte(), d2[1])
        // 300 = 0x0000012C，小端序
        assertEquals(0x2C.toByte(), d2[2])
        assertEquals(0x01.toByte(), d2[3])
        assertEquals(0x00.toByte(), d2[4])

        val crc = CRC32().apply { update(data) }.value.toInt()
        val expected = BytesUtils.fromUint32(crc)
        assertArrayEquals(expected, d2.copyOfRange(6, 10))
        // 尾部固定标记
        assertArrayEquals(byteArrayOf(0, 32, 0, -1), d2.copyOfRange(10, 14))
    }

    @Test
    fun `不同包类型只改第二个字节`() {
        val data = ByteArray(64)
        assertEquals(0xA0.toByte(), BytesUtils.getD2(data, 0xA0.toByte())[1])
        assertEquals(0xFD.toByte(), BytesUtils.getD2(data, 0xFD.toByte())[1])
    }

    @Test
    fun `按 8KB 分包且末片保留余数`() {
        val parts = BytesUtils.splitBytes(ByteArray(8192 * 2 + 100))
        assertEquals(3, parts.size)
        assertEquals(8192, parts[0].size)
        assertEquals(8192, parts[1].size)
        assertEquals(100, parts[2].size)
    }

    @Test
    fun `整倍数分包不会多出空片`() {
        val parts = BytesUtils.splitBytes(ByteArray(8192 * 2))
        assertEquals(2, parts.size)
        assertEquals(8192, parts[1].size)
    }

    @Test
    fun `空数组不产生分片`() {
        assertEquals(0, BytesUtils.splitBytes(ByteArray(0)).size)
    }

    @Test
    fun `fromUint32 与 hexToInt 互为逆运算`() {
        val bytes = BytesUtils.fromUint32(0xF1A2B3C4L.toInt())
        assertArrayEquals(byteArrayOf(0xC4.toByte(), 0xB3.toByte(), 0xA2.toByte(), 0xF1.toByte()), bytes)
        assertEquals("C4B3A2F1", BytesUtils.bytesToHexStr(bytes))
    }

    @Test
    fun `默认密钥不会被调用方改坏`() {
        val first = BytesUtils.getSecretKey("")
        first[0] = 0x00
        val second = BytesUtils.getSecretKey("")
        assertNotEquals(first[0], second[0])
    }
}
