package com.github.sky130.suiteki.pro.util

/**
 * 小米运动健康日志解析。
 *
 * 从 Wearable.log / XiaomiFit.device.log 里捞 `mac=..., model='...', authKey=...` 这类记录。
 * 这里是纯字符串处理，不碰 Android API，单测可以直接喂文本。
 */
object MiLogParser {

    data class Entry(
        val name: String,
        val mac: String,
        val key: String,
        val driverId: String,
    )

    /** 用 authKey 还是 encryptKey 做配对密钥 —— 华米系只要 authKey，小米系要 encryptKey */
    private enum class KeyField { AUTH, ENCRYPT }

    private data class Model(
        val prefix: String,
        val suffix: String,
        val driverId: String,
        val keyField: KeyField,
    )

    /**
     * model 字符串 → 展示名 / 驱动。
     * 展示名 = prefix + MAC 后四位 + suffix。
     */
    private val MODELS: Map<String, Model> = buildMap {
        val band8 = Model("Xiaomi Smart Band 8 ", "", "band8", KeyField.ENCRYPT)
        listOf(
            "miwear.watch.m66", "miwear.watch.m66nfc", "miwear.watch.m66tc",
            "miwear.watch.m66dsn", "miwear.watch.m66gl", "miwear.watch.m66gln",
        ).forEach { put(it, band8) }

        val band8Pro = Model("Xiaomi Smart Band 8 Pro ", "", "band8pro", KeyField.ENCRYPT)
        listOf("lchz.watch.m67", "lchz.watch.m67gl", "lchz.watch.m67ys")
            .forEach { put(it, band8Pro) }

        val band7 = Model("Xiaomi Smart Band 7 ", "", "band7", KeyField.AUTH)
        listOf("hmpace.watch.v7", "hmpace.watch.v7nfc").forEach { put(it, band7) }

        val redmiWatch4 = Model("Redmi Watch 4 ", "", "redmiwatch4", KeyField.ENCRYPT)
        listOf("lchz.watch.n65", "lchz.watch.n65gl").forEach { put(it, redmiWatch4) }

        val redmiWatch4Active = Model("Redmi Watch 4 ", " Active", "redmiwatch4", KeyField.ENCRYPT)
        listOf("lchz.watch.n65acn", "lchz.watch.n65agl", "lchz.watch.n65ain")
            .forEach { put(it, redmiWatch4Active) }

        val watchS3 = Model("Xiaomi Watch S3 ", " Active", "watchs3", KeyField.ENCRYPT)
        // 原来这里写成 "mijia.watch.n62,"（多了个逗号），S3 一直匹配不到
        listOf("mijia.watch.n62", "mjjia.watch.n62lte", "mijia.watch.n62cg")
            .forEach { put(it, watchS3) }
    }

    /**
     * 解析日志正文。同一 MAC 只留一条，密钥取第一个非空值
     * （日志里同设备会打好几行，靠前的行可能还没写出 key）。
     */
    fun parse(text: CharSequence): List<Entry> {
        val found = LinkedHashMap<String, Entry>()
        for (line in text.lineSequence()) {
            val mac = between(line, "mac=", ", ")
            if (mac.length != 17) continue
            val model = between(line, "model='", "', ")
            if (model.isEmpty()) continue
            val def = MODELS[model] ?: continue

            val key = when (def.keyField) {
                KeyField.AUTH -> between(line, "authKey=", ", ")
                KeyField.ENCRYPT -> between(line, "encryptKey=", ", ")
            }
            val name = def.prefix + mac.substring(12, 14) + mac.substring(15) + def.suffix
            val entry = Entry(name, mac, key, def.driverId)

            val id = mac.uppercase()
            val old = found[id]
            if (old == null) {
                found[id] = entry
            } else if (old.key.isBlank() && key.isNotBlank()) {
                // 同一台设备在日志里会出现多行，靠前的可能还没写出密钥
                found[id] = old.copy(key = key)
            }
        }
        return found.values.toList()
    }

    /** 取 left 与 right 之间的第一段，等价于 TextUtils.getRegexMatchText 的语义 */
    internal fun between(source: String, left: String, right: String): String {
        if (source.isEmpty() || left.isEmpty() || right.isEmpty()) return ""
        val start = source.indexOf(left)
        if (start < 0) return ""
        val from = start + left.length
        val end = source.indexOf(right, from)
        if (end < 0) return ""
        return source.substring(from, end)
    }
}
