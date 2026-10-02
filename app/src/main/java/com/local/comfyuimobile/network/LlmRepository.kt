package com.local.comfyuimobile.network

import com.local.comfyuimobile.model.LlmConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * v0.1.88：外部大模型的薄网络层。报文构造与解析都在 [LlmProtocol] 里。
 */
class LlmRepository {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * v0.1.88 安全要点：这个 client **绝对不能**复用 ComfyClient 里那个。
     *
     * ComfyClient 挂了个无差别附加 Cookie 的拦截器（云端反代登录态，见
     * `ComfyClient` 的 `addInterceptor`）。拿它去请求第三方大模型端点，等于把
     * 百度 AI Studio 的登录 Cookie 拱手送给对方。同理这里一个拦截器都不加，
     * 也不共用连接池。
     */
    private val client = OkHttpClient.Builder()
        // 推理类模型（deepseek-r1 那种）会先"想"很久再吐字，30 秒必超时。
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun chat(config: LlmConfig, systemPrompt: String, userMessage: String): String =
        chat(config, systemPrompt, userMessage, temperature = null, maxTokens = null)

    /**
     * @param temperature 覆盖配置里的值；null 表示用配置值。
     * @param maxTokens 回复长度上限；null 表示用配置值。
     *
     * 终端助手会传 [LlmProtocol.ASSISTANT_TEMPERATURE] 与 [LlmProtocol.ASSISTANT_MAX_TOKENS]：
     * 生成运维命令要稳定、要短，跟写提示词的需求是反的。
     */
    suspend fun chat(
        config: LlmConfig,
        systemPrompt: String,
        userMessage: String,
        temperature: Float?,
        maxTokens: Int?,
    ): String {
        if (!config.isConfigured()) throw LlmException("还没有配置大模型接口：请到设置里填接口地址和模型名")
        val request = Request.Builder()
            .url(LlmProtocol.chatEndpoint(config.baseUrl))
            .addHeader("Content-Type", "application/json")
            .apply { LlmProtocol.authHeader(config)?.let { addHeader("Authorization", it) } }
            .post(
                LlmProtocol.buildRequestBody(
                    config = config,
                    systemPrompt = systemPrompt,
                    userMessage = userMessage,
                    temperature = temperature,
                    maxTokens = maxTokens,
                ).toRequestBody(jsonMedia),
            )
            .build()
        return withContext(Dispatchers.IO) {
            awaitCall(request).use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw LlmException(LlmProtocol.describeHttpError(response.code, raw))
                }
                LlmProtocol.parseContent(raw)
            }
        }
    }

    /**
     * 拉取服务商的模型列表（v0.2.56）。
     *
     * 以前只能手打模型名——填错一个字就是 404 或模型不存在，而各家模型名
     * （`gpt-4o-mini`、`deepseek-chat`、`Qwen/Qwen2.5-7B-Instruct`）很难记。
     * 这里请求 OpenAI 标准的 `/v1/models`，把 id 列出来供选择。
     *
     * 只需要地址与 Key（不要求模型名已填），否则用户会卡在"想拉列表但还没填模型名"。
     */
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> {
        val endpoint = LlmProtocol.modelsEndpoint(baseUrl)
        if (endpoint.isBlank()) throw LlmException("先填接口地址，再拉取模型列表")
        val builder = Request.Builder().url(endpoint).get()
        LlmProtocol.authHeader(apiKey)?.let { builder.addHeader("Authorization", it) }
        val request = builder.build()
        return withContext(Dispatchers.IO) {
            awaitCall(request).use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw LlmException(LlmProtocol.describeHttpError(response.code, raw))
                }
                val models = LlmProtocol.parseModels(raw)
                if (models.isEmpty()) {
                    // 拉到了 200 但解析不出模型：如实告知，而不是假装成功返回空列表。
                    throw LlmException("接口返回成功但没解析出模型列表，可能不支持 /v1/models；可以手填模型名")
                }
                models
            }
        }
    }

    /**
     * OkHttp 的 `execute()` 不理协程取消 —— 用户关掉对话框之后线程还傻等在那儿。
     * 用 `enqueue` + `suspendCancellableCoroutine` 包一层，取消才是真取消。
     */
    private suspend fun awaitCall(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                // 用 Continuation 接口的 resumeWith，而不是 kotlinx 的 resume 扩展 ——
                // 后者在 1.9 里多了个没有默认值的 onCancellation 参数，还得额外 import。
                if (cont.isActive) cont.resumeWith(Result.success(response))
            }
        })
    }
}
