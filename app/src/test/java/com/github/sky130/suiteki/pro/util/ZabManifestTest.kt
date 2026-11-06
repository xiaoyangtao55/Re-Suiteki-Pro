package com.github.sky130.suiteki.pro.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * .zab 里 app.json 的解析。
 *
 * 关注两件事：appId 十进制 → 8 位十六进制（设备侧就是这么存的），
 * 以及 appName 缺失时要能从 i18n 里兜底。
 */
class ZabManifestTest {

    private fun zab(json: String, entryName: String = "app.json"): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return bos.toByteArray()
    }

    @Test
    fun `十进制 appId 转成八位十六进制`() {
        val meta = ZabManifest.parse(
            zab("""{"app":{"appId":1000089,"appName":"弥散曲线","version":{"name":"1.0.0"}}}""")
        )
        assertEquals("000F4299", meta?.id)
        assertEquals("弥散曲线", meta?.name)
        assertEquals("1.0.0", meta?.version)
    }

    @Test
    fun `高于 Int 上限的 appId 不会被截断成负数`() {
        // 0xFFFFFFFF 附近的值：用 Int 解析会溢出，这里必须走 Long
        val meta = ZabManifest.parse(
            zab("""{"app":{"appId":4294967295,"appName":"big"}}""")
        )
        assertEquals("FFFFFFFF", meta?.id)
    }

    @Test
    fun `appName 缺失时从 i18n 兜底`() {
        val meta = ZabManifest.parse(
            zab(
                """
                {"app":{"appId":1000089},
                 "i18n":{"zh-CN":{"appName":"中文名"}}}
                """.trimIndent()
            )
        )
        assertEquals("中文名", meta?.name)
    }

    @Test
    fun `appType 为 watchface 时标记表盘`() {
        val meta = ZabManifest.parse(
            zab("""{"app":{"appId":1,"appName":"wf","appType":"watchface"}}""")
        )
        assertTrue(meta!!.watchface)
    }

    @Test
    fun `普通小程序不标记表盘`() {
        val meta = ZabManifest.parse(
            zab("""{"app":{"appId":1,"appName":"app","appType":"app"}}""")
        )
        assertFalse(meta!!.watchface)
    }

    @Test
    fun `appId 缺失或非正数返回 null`() {
        assertNull(ZabManifest.parse(zab("""{"app":{"appName":"x"}}""")))
        assertNull(ZabManifest.parse(zab("""{"app":{"appId":0,"appName":"x"}}""")))
    }

    @Test
    fun `没有 app 节点返回 null`() {
        assertNull(ZabManifest.parse(zab("""{"i18n":{}}""")))
    }

    @Test
    fun `app_json 放在子目录里也能找到`() {
        val meta = ZabManifest.parse(
            zab("""{"app":{"appId":16,"appName":"nested"}}""", entryName = "assets/app.json")
        )
        assertEquals("00000010", meta?.id)
    }

    @Test
    fun `不是 zip 的输入返回 null 而不抛异常`() {
        assertNull(ZabManifest.parse(ByteArray(64) { 0x00 }))
        assertNull(ZabManifest.parse(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `空 zip 返回 null`() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { }
        assertNull(ZabManifest.parse(bos.toByteArray()))
    }

    @Test
    fun `畸形 json 返回 null`() {
        assertNull(ZabManifest.parse(zab("{not json")))
    }
}
