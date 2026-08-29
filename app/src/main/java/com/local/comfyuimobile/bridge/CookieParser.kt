package com.local.comfyuimobile.bridge

/**
 * Cookie 字符串清洗工具（纯 Kotlin，可单元测试）。
 *
 * 浏览器复制的一整串 Cookie（`a=1; b=2; c=3`）不能直接塞给
 * CookieManager.setCookie —— 那个 API 只接受单个 `name=value`，
 * 整串塞进去遇到带引号（`RT="z=1&..."`）或畸形段（`undefined`）会整体失效。
 * 这里负责按分号拆分、清洗出合法的 name=value 段。
 */
object CookieParser {

    /** 把一整串 Cookie 拆成合法的 `name=value` 列表。 */
    fun parse(cookieHeader: String): List<String> = buildList {
        cookieHeader.split(';').forEach { raw ->
            val seg = raw.trim()
            val eq = seg.indexOf('=')
            if (eq <= 0) return@forEach // 无 '=' 或 name 为空：畸形段，跳过
            val name = seg.substring(0, eq).trim()
            if (name.isBlank()) return@forEach
            // 值里若带了包裹引号（如 RT="..."），剥掉，避免注入后语义错乱。
            var value = seg.substring(eq + 1).trim()
            if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
                value = value.substring(1, value.length - 1)
            }
            if (value.isBlank()) return@forEach
            add("$name=$value")
        }
    }
}
