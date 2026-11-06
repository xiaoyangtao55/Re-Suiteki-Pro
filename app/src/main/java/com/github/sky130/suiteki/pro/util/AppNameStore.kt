package com.github.sky130.suiteki.pro.util

import android.content.Context
import com.github.sky130.suiteki.pro.MainApplication.Companion.context

/**
 * 手环应用 id -> 名字的本地对照表。
 *
 * 0x00A0 的列表只给 id 和版本号，名字在协议里根本不存在。所以名字只能来自两处：
 * 1. 安装 .zab 时解析包内 app.json 的 app.appName
 * 2. 用户在"应用管理"里手动命名
 * 两条都写到这里，手动命名会覆盖解析结果（用户说了算）。
 */
object AppNameStore {
    private const val PREFS = "suiteki_app_names"

    /**
     * 内置对照表，数据来自真机安装包的 app.json。
     * 只是兜底：用户手动命名 / 安装时解析出的名字都优先于它。
     */
    private val builtIn = mapOf(
        "01310AB4" to "恶魔轮盘",
        "00005E59" to "扫雷",
        "00005B08" to "存储空间",
    )

    private val prefs by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun get(id: String): String? {
        val key = id.uppercase()
        return prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: builtIn[key]
    }

    fun put(id: String, name: String) {
        val key = id.uppercase()
        if (name.isBlank()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, name.trim()).apply()
        }
    }
}
