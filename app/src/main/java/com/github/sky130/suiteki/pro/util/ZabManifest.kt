package com.github.sky130.suiteki.pro.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 解析 .zab（Zepp OS 小程序的 zip 容器）里的 app.json。
 *
 * appId 是十进制数字，设备侧用 8 位十六进制表示，所以要转一下：
 * 1000089 -> 0xF4299 -> "000F4299"。
 *
 * 这里刻意不依赖 `SuitekiManager` / `org.json`：解析是纯数据变换，
 * 保持无副作用才能在 JVM 单测里直接跑（Android 的 org.json 在本地单测里是空壳桩）。
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
        val json = try {
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
        } catch (_: Exception) {
            null
        }
        return json
    }

    private fun parseJson(text: String): Meta? {
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        val app = root.objectOrNull("app") ?: return null
        val appId = app.get("appId")?.takeIf { it.isJsonPrimitive }?.asLong ?: -1L
        if (appId <= 0L) return null

        // 小数端序无关，只是把十进制 id 转成设备上使用的 8 位十六进制
        val id = java.lang.Long.toHexString(appId).uppercase().padStart(8, '0').takeLast(8)

        val name = app.stringOrNull("appName")
            ?: root.objectOrNull("i18n")?.objectOrNull("zh-CN")?.stringOrNull("appName")
            ?: ""
        val version = app.objectOrNull("version")?.stringOrNull("name") ?: ""
        val watchface = app.stringOrNull("appType")?.equals("watchface", ignoreCase = true) == true
        return Meta(id, name, version, watchface)
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.objectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
