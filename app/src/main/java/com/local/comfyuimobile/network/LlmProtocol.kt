package com.local.comfyuimobile.network

import com.local.comfyuimobile.model.LlmConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.1.88：大模型接口的报文构造与解析。
 *
 * 这里刻意**不引用 OkHttp，也不引用任何 Android API** —— 全部是纯 Kotlin +
 * org.json，因此可以直接在本地 JVM 上跑单元测试（CI 之外的本地验证环境拿不到
 * OkHttp 的 jar，凡是沾了网络库的类都编译不了）。
 * 发请求本身在 [LlmRepository] 里，那一层薄到几乎不需要测。
 */
object LlmProtocol {

    private const val CHAT_COMPLETIONS = "chat/completions"

    /**
     * 把用户填的地址规范化成完整的 `/chat/completions` 端点。
     *
     * 各家给的地址形态很乱：有人填 `https://api.openai.com/v1`，有人直接把完整
     * URL 粘进来，还有自建网关是 `https://x.com/api/v1`。统一成"没有就补 `/v1`"
     * 两种补法，避免用户卡在"为什么一直 404"。
     */
    fun chatEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        return when {
            trimmed.endsWith(CHAT_COMPLETIONS) -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/$CHAT_COMPLETIONS"
            trimmed.endsWith("/v1/") -> "$trimmed/$CHAT_COMPLETIONS"
            else -> "$trimmed/v1/$CHAT_COMPLETIONS"
        }
    }

    fun buildRequestBody(config: LlmConfig, systemPrompt: String, userMessage: String): String =
        JSONObject()
            .put("model", config.model.trim())
            .put("temperature", LlmConfig.normalizeTemperature(config.temperature).toDouble())
            .put("stream", false)
            .put(
                "messages",
                JSONArray().apply {
                    if (systemPrompt.isNotBlank()) {
                        put(JSONObject().put("role", "system").put("content", systemPrompt))
                    }
                    put(JSONObject().put("role", "user").put("content", userMessage))
                },
            )
            .toString()

    fun authHeader(config: LlmConfig): String? =
        config.apiKey.trim().takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    /**
     * 从响应体里抠出模型正文。
     *
     * 兼容三种常见形态：标准 `choices[0].message.content`、推理模型把正文放在
     * `reasoning_content`、以及 /v1/completions 风格的 `choices[0].text`。
     */
    fun parseContent(raw: String): String {
        val root = runCatching { JSONObject(raw) }
            .getOrNull()
            ?: throw LlmException("大模型返回了非 JSON 内容：${raw.trim().take(120)}")
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        listOfNotNull(
            message?.optString("content"),
            message?.optString("reasoning_content"),
            choice?.optString("text"),
        ).firstOrNull { it.isNotBlank() }
            ?.let { return stripCodeFence(it) }
        val reported = root.optJSONObject("error")?.optString("message").orEmpty()
        throw LlmException(
            if (reported.isNotBlank()) "大模型报错：$reported" else "大模型返回内容为空",
        )
    }

    /**
     * 模型很爱给答案套一层 ``` 代码块 —— 直接写进提示词的话，反引号和 "text"
     * 这个词会变成画面里的元素，所以一律剥掉。
     */
    fun stripCodeFence(text: String): String {
        var result = text.trim()
        if (result.startsWith("```")) {
            val firstNewline = result.indexOf('\n')
            result = if (firstNewline >= 0) result.substring(firstNewline + 1) else result.removePrefix("```")
        }
        if (result.endsWith("```")) result = result.dropLast(3)
        return result.trim()
    }

    fun describeHttpError(code: Int, raw: String): String {
        val reported = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }
            .getOrNull()
            .orEmpty()
        val detail = reported.ifBlank { raw.trim().take(160) }
        val suffix = when (code) {
            401, 403 -> "（多半是 API Key 不对或没权限）"
            404 -> "（地址不对，检查一下是不是少了 /v1）"
            429 -> "（触发限流，等一会儿再试）"
            in 500..599 -> "（服务端出错，稍后再试）"
            else -> ""
        }
        return buildString {
            append("大模型接口 HTTP ").append(code)
            if (detail.isNotBlank()) append("：").append(detail)
            if (suffix.isNotBlank()) append(' ').append(suffix)
        }
    }
}

class LlmException(message: String) : IllegalStateException(message)
