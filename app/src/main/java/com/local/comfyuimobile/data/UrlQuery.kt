package com.local.comfyuimobile.data

/**
 * URL 查询参数拼接（v0.2.51）。
 *
 * 以前预览缩略图直接写 `"${url}&preview=webp;90"`——一旦 URL 本身没有查询参数，
 * 拼出来就是 `/view&preview=...` 这种非法地址，参数丢失、图取不到。这里按 URL 是否
 * 已有 `?` 决定用 `?` 还是 `&`。
 *
 * 不引 Uri（Android 依赖）：本对象保持纯 Kotlin，可单测。
 */
object UrlQuery {
    fun append(rawUrl: String, name: String, value: String): String {
        if (rawUrl.isBlank()) return rawUrl
        val separator = if (rawUrl.contains('?')) '&' else '?'
        return "$rawUrl$separator$name=$value"
    }
}
