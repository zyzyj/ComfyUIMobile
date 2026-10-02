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
    private const val MODELS = "models"

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

    /**
     * 模型列表端点 `/models`（v0.2.56）。
     *
     * 与 [chatEndpoint] 同一套地址归一规则，只是末尾换成 `models`：用户填的地址
     * 可能是聊天端点、`/v1`、或裸域名，三种都要能拼对。
     */
    fun modelsEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        return when {
            trimmed.endsWith("/$CHAT_COMPLETIONS") ->
                trimmed.removeSuffix("/$CHAT_COMPLETIONS") + "/$MODELS"
            trimmed.endsWith("/v1") -> "$trimmed/$MODELS"
            trimmed.endsWith("/v1/") -> "$trimmed/$MODELS"
            trimmed.endsWith("/$MODELS") -> trimmed
            else -> "$trimmed/v1/$MODELS"
        }
    }

    /**
     * 从 `/models` 响应里取出模型 id 列表。
     *
     * 兼容两种常见形态：OpenAI 官方 `{data:[{id:"gpt-4o"}]}`，以及部分服务商直接
     * 返回数组 `[{id:"x"}]`；再兜底一种 `{models:[...]}`。
     * 取不到时返回空列表（由调用方决定提示什么），不抛异常——列表拉失败不该把
     * 整个设置页卡住，用户手填模型名仍然可用。
     */
    fun parseModels(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val entries: List<JSONObject> = runCatching {
            when {
                trimmed.startsWith("[") -> {
                    val array = JSONArray(trimmed)
                    List(array.length()) { index -> array.optJSONObject(index) }
                }
                else -> {
                    val root = JSONObject(trimmed)
                    val array = root.optJSONArray("data")
                        ?: root.optJSONArray("models")
                        ?: return emptyList()
                    List(array.length()) { index -> array.optJSONObject(index) }
                }
            }
        }.getOrElse { emptyList() }
        val ids = entries.mapNotNull { item ->
            item ?: return@mapNotNull null
            // 少数服务商用 model/name 而不是 id；也兼容字符串元素的情况。
            item.optString("id").ifBlank { item.optString("model") }
                .ifBlank { item.optString("name") }
                .takeIf { it.isNotBlank() }
        }
        return ids.distinct().sorted()
    }

    fun buildRequestBody(config: LlmConfig, systemPrompt: String, userMessage: String): String =
        buildRequestBody(
            config = config,
            systemPrompt = systemPrompt,
            userMessage = userMessage,
            // 不传即用配置值（写提示词走这条路）。
            temperature = null,
            maxTokens = null,
        )

    /**
     * 拼 chat 请求体。
     *
     * [temperature] / [maxTokens] 传 null 表示"用配置里那份"——写提示词走这条。
     * 终端助手会显式传更低的值，原因见 [ASSISTANT_TEMPERATURE] 与 [ASSISTANT_MAX_TOKENS]。
     */
    fun buildRequestBody(
        config: LlmConfig,
        systemPrompt: String,
        userMessage: String,
        temperature: Float?,
        maxTokens: Int?,
    ): String =
        JSONObject()
            .put("model", config.model.trim())
            .put(
                "temperature",
                LlmConfig.normalizeTemperature(temperature ?: config.temperature).toDouble(),
            )
            .put("stream", false)
            .apply {
                val limit = maxTokens ?: config.maxTokens
                // v0.2.70：给个上限。以前完全不限制，模型偶尔会洋洋洒洒写一大篇——
                // 既烧 token，又把真正要执行的命令埋在长文里。
                if (limit > 0) put("max_tokens", limit)
            }
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

    /**
     * 终端助手用的 temperature：**明显低于**写提示词（配置里的 0.9）。
     *
     * 两个场景对"随机性"的需求是相反的：
     *  - 写提示词要创意，0.9 合适；
     *  - 生成运维命令要**可预期**——同一个问题每次给同一条命令才好核对，
     *    0.9 会让模型偶尔冒出没 Asked 的"创新"命令（如换个包管理器、多加个参数）。
     * 0.2 足够稳定，又不会死板到只会套模板。
     */
    const val ASSISTANT_TEMPERATURE = 0.2f

    /**
     * 终端助手回复的 token 上限（v0.2.70）。
     *
     * 守则本来就要求"回答简短、一次一条命令"，1024 token 绰绰有余；
     * 顺带挡住模型把命令埋进长篇解释里。
     */
    const val ASSISTANT_MAX_TOKENS = 1_024

    fun authHeader(config: LlmConfig): String? = authHeader(config.apiKey)

    /** 直接给 API Key 的重载（拉取模型列表时只有 Key、还没有完整配置）。 */
    fun authHeader(apiKey: String): String? =
        apiKey.trim().takeIf { it.isNotBlank() }?.let { "Bearer $it" }

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
