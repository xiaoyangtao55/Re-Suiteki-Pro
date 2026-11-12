package com.github.sky130.suiteki.pro.util

import android.content.Context
import com.github.sky130.suiteki.pro.MainApplication.Companion.context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 安装时留下的本地记录：这个 id 是装上去的表盘还是小程序、装的时候叫什么。
 *
 * 为什么需要它：
 * 1. 0x00A0 的列表只有 id 和版本号，协议里没有名字字段；
 * 2. 表盘 `.bin` 包里没有 app.json，拿不到 appId 和名字，只能靠「安装前后列表差异」
 *    定位新 id，再用文件名当名字；
 * 3. 小程序虽然有 app.json，但不少第三方包缺字段或 appName 是 "test"，
 *    解析不出来时同样退到文件名。
 *
 * 与 [AppNameStore] 的分工：
 * - AppNameStore 存「id -> 名字」，手动命名优先，可被用户改；
 * - 这里存「id -> 名字 + 类型」，只在安装时写一次，用来兜底和区分表盘/应用。
 * 显示优先级：手动命名 > 安装记录 > 原始 id。
 */
object InstallRecordStore {
    private const val PREFS = "suiteki_install_records"
    private const val KEY = "records"

    /** 与 HuamiService.TYPE_D2_* 对应的业务类型，存字符串避免枚举序列化问题 */
    const val KIND_WATCHFACE = "watchface"
    const val KIND_APP = "app"

    data class Record(
        val id: String = "",
        val name: String = "",
        val kind: String = "",
        val time: Long = 0L,
    ) {
        /** 界面上的中文类型标签；固件等其它类型不显示 */
        val kindLabel: String?
            get() = when (kind) {
                KIND_WATCHFACE -> "表盘"
                KIND_APP -> "应用"
                else -> null
            }
    }

    private val gson = Gson()

    /** 应用管理页每个条目都要查一次，不能每次都反序列化整张表 */
    private var cache: MutableMap<String, Record>? = null

    private val prefs by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun put(id: String, name: String, kind: String) {
        val key = id.uppercase()
        if (key.isBlank()) return
        val all = load()
        all[key] = Record(
            id = key,
            name = name.trim().takeIf { it.isNotBlank() } ?: all[key]?.name.orEmpty(),
            kind = kind,
            time = System.currentTimeMillis(),
        )
        save(all)
    }

    fun get(id: String): Record? = load()[id.uppercase()]

    fun remove(id: String) {
        val all = load()
        if (all.remove(id.uppercase()) != null) save(all)
    }

    /** 给测试和排查用，正常界面不直接读全量 */
    fun all(): Map<String, Record> = load()

    private fun load(): MutableMap<String, Record> {
        cache?.let { return it }
        val raw = prefs.getString(KEY, null)
        val map = if (raw == null) {
            mutableMapOf()
        } else {
            runCatching {
                val type = object : TypeToken<Map<String, Record>>() {}.type
                gson.fromJson<Map<String, Record>>(raw, type)?.toMutableMap() ?: mutableMapOf()
            }.getOrElse { mutableMapOf() }
        }
        cache = map
        return map
    }

    private fun save(all: Map<String, Record>) {
        prefs.edit().putString(KEY, gson.toJson(all)).apply()
    }
}
