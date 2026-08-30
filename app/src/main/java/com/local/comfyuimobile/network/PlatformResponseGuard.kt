package com.local.comfyuimobile.network

/**
 * 识别「服务器返回网页而非数据」的情况，并给出人能看懂的错误。
 *
 * 反向代理托管的 ComfyUI（百度 AI Studio、CloudStudio 等）有个通病：
 * 不支持的接口不会返回 JSON 错误，而是返回一个 HTML 页面——有时是 404 错误页，
 * 有时更坑，是 **HTTP 200 + 登录页**。
 *
 * 以前的处理有两个问题：
 *  1. 只检查 HTTP 码，200 + HTML 会被当成合法响应，后面 JSONObject 解析直接抛
 *     JSONException，用户看到的是「连接失败」而不是「需要登录」；
 *  2. 报错时把整个 HTML 塞进异常消息，一次 34 行、刷 56 次就把诊断日志冲垮，
 *     真正有用的信息全被埋掉。
 *
 * 这里统一按内容判定，并把错误压缩成一行人话。纯 Kotlin，可单测。
 */
object PlatformResponseGuard {

    /** 报错时最多保留的正文长度，避免把整个网页灌进日志。 */
    const val MAX_BODY_CHARS = 120

    /** 判定为"不支持此接口"的 HTTP 码。 */
    private val UNSUPPORTED_CODES = setOf(400, 401, 403, 404, 405, 501)

    enum class BodyKind { JSON, HTML, EMPTY, TEXT, REDIRECT }

    fun classify(body: String): BodyKind {
        if (body.isBlank()) return BodyKind.EMPTY
        val head = body.trimStart().take(64).lowercase()
        if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<head")) {
            return BodyKind.HTML
        }
        if (head.startsWith("{") || head.startsWith("[")) return BodyKind.JSON
        // 少数代理会用一段 JS 做跳转，正文里通常带 location.replace / http-equiv=refresh
        val lower = body.lowercase()
        if (lower.contains("location.replace") || lower.contains("http-equiv=\"refresh\"")) {
            return BodyKind.REDIRECT
        }
        return BodyKind.TEXT
    }

    fun isHtml(body: String): Boolean = classify(body) == BodyKind.HTML

    /** 登录页特征：AI Studio 走 passport，多数代理会渲染表单 + 密码框。 */
    fun looksLikeLoginPage(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("passport") ||
            lower.contains("请登录") ||
            lower.contains("登录失效") ||
            (lower.contains("<form") && lower.contains("password")) ||
            (lower.contains("登录") && lower.contains("password"))
    }

    /** 把响应压缩成一句人话。 */
    fun describe(httpCode: Int, body: String): String {
        if (body.isBlank()) return "服务器返回空内容（HTTP $httpCode）"
        if (!isHtml(body)) {
            // ComfyUI 自己的错误是 {"error":{"message":...}}，优先取 message，
            // 保持与改动前一致的可读性；取不到再退回正文。
            val message = runCatching {
                org.json.JSONObject(body).optJSONObject("error")?.optString("message")
            }.getOrNull()?.takeIf { it.isNotBlank() }
            val flat = (message ?: body.trim()).replace(Regex("\\s+"), " ")
            return "HTTP $httpCode：" + flat.take(MAX_BODY_CHARS)
        }
        return when {
            looksLikeLoginPage(body) ->
                "需要登录或登录已失效（HTTP $httpCode，服务器返回的是登录页）"
            httpCode in UNSUPPORTED_CODES ->
                "该服务器不支持此接口（HTTP $httpCode，返回的是网页错误页）"
            else ->
                "服务器返回网页而非数据（HTTP $httpCode）"
        }
    }

    /**
     * 统一守门：HTTP 码不合法**或**正文是网页，都视为失败。
     * 返回 true 表示响应可用。
     */
    @Throws(PlatformResponseException::class)
    fun guard(httpCode: Int, body: String, allowedCodes: Set<Int> = setOf(200)) {
        val html = isHtml(body)
        if (httpCode !in allowedCodes) {
            throw PlatformResponseException(
                describe(httpCode, body), httpCode, isUnsupported(httpCode, body), html,
            )
        }
        // 关键：200 + HTML 同样是失败，否则后面解析会抛出难以理解的 JSONException
        if (html) {
            throw PlatformResponseException(describe(httpCode, body), httpCode, true, html)
        }
    }

    /**
     * 判定"这个平台根本不支持该接口"。
     *
     * 只看正文是不是网页，不看 HTTP 码：ComfyUI 自己的 API 路由从不返回 HTML，
     * 而反向代理（AI Studio 等）不支持的接口一律返回整页 HTML。反过来，404 也可能
     * 是正常业务错误——比如 workflows 目录还没建、或者文件刚被别的设备删了，
     * 这类 404 的正文是 JSON，属于暂时性错误，重试就好，不该据此禁用整个功能。
     */
    private fun isUnsupported(httpCode: Int, body: String): Boolean = isHtml(body)

    /** 该接口是否被这个平台支持——不支持的不该反复重试。 */
    fun isUnsupportedResponse(httpCode: Int, body: String): Boolean = isUnsupported(httpCode, body)
}

/**
 * 平台响应异常。
 *
 * @param unsupported true 表示"这个平台根本不支持该接口"，调用方应停止重试
 *                    并把它记进能力表，而不是退避后继续刷。
 * @param html true 表示服务器返回的是网页而不是数据。它和 unsupported 不是一回事：
 *             实例冷启动时 AI Studio 也会吐一页 HTML，这种属于抖动，重试就能好；
 *             用 html 标记出来，调用方才知道该重试而不是直接判死刑。
 */
class PlatformResponseException(
    message: String,
    val httpCode: Int,
    val unsupported: Boolean = false,
    val html: Boolean = false,
) : IllegalStateException(message)
