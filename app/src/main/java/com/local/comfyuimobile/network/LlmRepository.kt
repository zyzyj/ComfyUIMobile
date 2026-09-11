package com.local.comfyuimobile.network

import com.local.comfyuimobile.model.LlmConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.resume
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

    suspend fun chat(config: LlmConfig, systemPrompt: String, userMessage: String): String {
        if (!config.isConfigured()) throw LlmException("还没有配置大模型接口：请到设置里填接口地址和模型名")
        val request = Request.Builder()
            .url(LlmProtocol.chatEndpoint(config.baseUrl))
            .addHeader("Content-Type", "application/json")
            .apply { LlmProtocol.authHeader(config)?.let { addHeader("Authorization", it) } }
            .post(LlmProtocol.buildRequestBody(config, systemPrompt, userMessage).toRequestBody(jsonMedia))
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
                if (cont.isActive) cont.resume(response)
            }
        })
    }
}
