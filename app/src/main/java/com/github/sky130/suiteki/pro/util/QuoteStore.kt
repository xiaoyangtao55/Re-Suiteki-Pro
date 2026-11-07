package com.github.sky130.suiteki.pro.util

import android.content.Context
import com.github.sky130.suiteki.pro.MainApplication.Companion.context

/**
 * 一言看到第几句。
 *
 * 只放在 remember 里的话，页面一离开组合就被丢掉，下次进来又从第 0 句开始——
 * 表现就是"每次进来都是同一句"。所以把位置存在本地，进来取下一句。
 */
object QuoteStore {
    private const val PREFS = "suiteki_quote"
    private const val KEY = "index"

    /** 取下一句的下标，并把它记下来。size 为 0 时返回 0。 */
    fun next(size: Int): Int {
        if (size <= 0) return 0
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 默认 -1：首次启动 ( -1 + 1 ) % size = 0，从第一句开始
        val next = (sp.getInt(KEY, -1) + 1).mod(size)
        sp.edit().putInt(KEY, next).apply()
        return next
    }
}
