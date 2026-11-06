package com.github.sky130.suiteki.pro.util

import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 解析 .zab（Zepp OS 小程序的 zip 容器）里的 app.json。
 *
 * appId 是十进制数字，设备侧用 8 位十六进制表示，所以要转一下：
 * 1000089 -> 0xF43D9 -> "000F43D9"。
 */
object ZabManifest {

    data class Meta(
        val id: String,
        val name: String,
        val version: String,
        val watchface: Boolean,
    )

    fun parse(bytes: ByteArray): Meta? {
        if (bytes.size < 4) return null
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory &&
                        entry.name.substringAfterLast('/') == "app.json"
                    ) {
                        return parseJson(zip.readBytes().toString(Charsets.UTF_8))
                    }
                    entry = zip.nextEntry
                }
                null
            }
        } catch (e: Exception) {
            SuitekiManager.log("zab-manifest-fail", e.message ?: e.toString())
            null
        }
    }

    private fun parseJson(text: String): Meta? {
        val root = JSONObject(text)
        val app = root.optJSONObject("app") ?: return null
        val appId = app.optLong("appId", -1L)
        if (appId <= 0L) return null

        // 小数端序无关，只是把十进制 id 转成设备上使用的 8 位十六进制
        val id = java.lang.Long.toHexString(appId).uppercase().padStart(8, '0').takeLast(8)

        val name = app.optString("appName").ifBlank {
            root.optJSONObject("i18n")
                ?.optJSONObject("zh-CN")
                ?.optString("appName")
                .orEmpty()
        }
        val version = app.optJSONObject("version")?.optString("name").orEmpty()
        val watchface = app.optString("appType").equals("watchface", ignoreCase = true)
        return Meta(id, name, version, watchface)
    }
}
