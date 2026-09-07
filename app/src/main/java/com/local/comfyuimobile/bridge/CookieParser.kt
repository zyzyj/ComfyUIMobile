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

    /**
     * v0.1.86：WebView 侧拿到的 Cookie 是否"确实比当前这份新"。
     *
     * 用于自动同步：平台（AI Studio 之类）每次页面重载都可能下发新的 Set-Cookie 给登录态
     * 续期，但那份新 Cookie 只活在 WebView 里，HTTP 侧用的还是用户手贴的旧值——过几小时
     * 就 403，用户只能再去平台复制一次。
     *
     * 判定规则是**单向**的：只有"新集合里出现了当前没有的键、或同键值不同"才算有更新；
     * 反过来（新集合只是当前的子集，比如 CookieManager 只返回了当前域下的部分 Cookie）
     * 不算——否则会把用户精心配置的完整 Cookie 冲成一个残缺版本，反而更容易失效。
     */
    fun hasNewPairs(fresh: String, current: String): Boolean {
        val freshPairs = parse(fresh).toSet()
        if (freshPairs.isEmpty()) return false
        val currentPairs = parse(current).toSet()
        if (currentPairs.isEmpty()) return true
        val currentValues = currentPairs.associate { pair ->
            val eq = pair.indexOf('=')
            pair.substring(0, eq) to pair.substring(eq + 1)
        }
        return freshPairs.any { pair ->
            val eq = pair.indexOf('=')
            val name = pair.substring(0, eq)
            val value = pair.substring(eq + 1)
            currentValues[name] != value
        }
    }
}
