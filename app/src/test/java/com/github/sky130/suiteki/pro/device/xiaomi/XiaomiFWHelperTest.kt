package com.github.sky130.suiteki.pro.device.xiaomi

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 文件格式识别。这里出过两个必现问题：
 * 1. fileType 初值是 -1 而拦截条件比的是 TYPE_UNKNOWN(0)，空文件照样发命令；
 * 2. rpk 解压依赖 /data/local/tmp，Android 上不可写，小程序包从来没被识别成功过。
 */
class XiaomiFWHelperTest {

    private fun helper(bytes: ByteArray) = XiaomiFWHelper(bytes).also { it.init() }

    private fun watchface(id: String = "123456", name: String = "MyFace"): ByteArray {
        val buf = ByteArray(160)
        buf[0] = 0x5A
        buf[1] = 0xA5.toByte()
        id.toByteArray().copyInto(buf, 40)
        name.toByteArray().copyInto(buf, 104)
        return buf
    }

    private fun firmware(version: String = "1.2.3.4"): ByteArray {
        val buf = ByteArray(64)
        buf[0] = 0x60
        buf[1] = 0x5A
        buf[2] = 0x5A
        buf[3] = 0x7E
        version.toByteArray().copyInto(buf, 4)
        return buf
    }

    private fun rpk(manifest: String?, entryName: String = "manifest.json"): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            if (manifest != null) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(manifest.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun `表盘按 5A A5 识别`() {
        val h = helper(watchface())
        assertEquals(XiaomiService.TYPE_WATCHFACE, h.fileType)
        assertEquals(XiaomiService.WATCHFACE_COMMAND_TYPE, h.type)
        assertEquals(XiaomiService.CMD_WATCHFACE_INSTALL, h.subType)
        assertEquals("123456", h.id)
    }

    @Test
    fun `固件按 60 5A 5A 7E 识别`() {
        val h = helper(firmware())
        assertEquals(XiaomiService.TYPE_FIRMWARE, h.fileType)
        assertEquals(XiaomiService.SYSTEM_COMMAND_TYPE, h.type)
        assertEquals(XiaomiService.CMD_FIRMWARE_INSTALL, h.subType)
        assertEquals("1.2.3.4", h.versionName)
    }

    @Test
    fun `小程序从内存里的 manifest 解析`() {
        val h = helper(
            rpk(
                """
                {"package":"com.example.demo","name":"示例","versionName":"1.0.0","versionCode":1000001}
                """.trimIndent()
            )
        )
        assertEquals(XiaomiService.TYPE_RPK, h.fileType)
        assertEquals(XiaomiService.RPK_COMMAND_TYPE, h.type)
        assertEquals(XiaomiService.CMD_RPK_INSTALL, h.subType)
        assertEquals("com.example.demo", h.id)
        assertEquals("示例", h.name)
        assertEquals(1000001, h.versionCode)
    }

    @Test
    fun `小程序包缺少 manifest 时不算识别成功`() {
        assertEquals(XiaomiService.TYPE_UNKNOWN, helper(rpk(null)).fileType)
    }

    @Test
    fun `小程序 manifest 缺少 package 时不算识别成功`() {
        assertEquals(
            XiaomiService.TYPE_UNKNOWN,
            helper(rpk("""{"name":"x","versionName":"1.0.0"}""")).fileType
        )
    }

    @Test
    fun `空文件是 UNKNOWN 而不是 -1`() {
        val h = helper(ByteArray(0))
        assertEquals(XiaomiService.TYPE_UNKNOWN, h.fileType)
        assertEquals(-1, h.type)
    }

    @Test
    fun `四到十字节的垃圾文件不会越界`() {
        // 固件头的版本号要读到第 11 字节，这里原来直接 ArrayIndexOutOfBounds
        for (size in 4..10) {
            val h = helper(ByteArray(size))
            assertEquals(XiaomiService.TYPE_UNKNOWN, h.fileType)
        }
    }

    @Test
    fun `乱码文件是 UNKNOWN`() {
        assertEquals(XiaomiService.TYPE_UNKNOWN, helper(ByteArray(512) { 0x41.toByte() }).fileType)
    }

    @Test
    fun `表盘 id 不是数字时不会误判成表盘`() {
        assertEquals(XiaomiService.TYPE_UNKNOWN, helper(watchface(id = "ABC")).fileType)
    }

    @Test
    fun `全零的表盘头不会被当成表盘`() {
        val buf = ByteArray(160)   // 0x00 0x00 开头
        assertEquals(XiaomiService.TYPE_UNKNOWN, helper(buf).fileType)
    }
}
