package com.local.comfyuimobile.network

import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.model.AiStudioAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * BML Codelab（JupyterLab 3.0）的内核通道。
 *
 * v0.1.98：控制台真正的「能在云端跑命令」能力。
 *
 * 链路（全部从前端 bundle 核实，不是猜的）：
 *  1. `POST /studio/project/running_status_check` → `result = {baseUrl, token, hubBaseUrl}`
 *     平台返回的 baseUrl 形如 `https://xxx.com/idehubcpu01/user/4560/209624/`，
 *     前端会 `split(".com")[1]` 只取域名之后的路径部分。
 *  2. `GET {hubBaseUrl}/api/kernels`（带 `auth: token`）列出运行中的内核。
 *  3. `POST {hubBaseUrl}/api/kernels` 新建内核。
 *
 * 注意：执行命令需要 WebSocket（`/api/kernels/{id}/channels`），
 * 这一版先只做「列内核 + 新建内核 + 读取文件」，把连接链打通；
 * 真正的代码执行是下一步。
 */
class AiStudioKernelClient {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 平台返回的环境连接信息。 */
    data class KernelEndpoint(
        /** 完整 baseUrl（含域名），用于 /api/contents 之类。 */
        val baseUrl: String,
        /** 仅路径部分（前端就是用它拼 kernels 的）。 */
        val basePath: String,
        val token: String,
        /** 内核接口所在主机（hubBaseUrl 的完整值）。 */
        val hubBaseUrl: String,
        val hubPath: String,
    ) {
        fun isUsable(): Boolean = hubBaseUrl.isNotBlank() && token.isNotBlank()
    }

    /**
     * 拉取环境连接信息。
     *
     * 平台在 `running_status_check` 的 result 里回 baseUrl/token/hubBaseUrl。
     * 前端会按 `.com` 切分只留路径——这里两种形态都保留，避免平台换域名后缀时拼错 URL。
     */
    suspend fun fetchEndpoint(account: AiStudioAccount, projectId: String, scheduleName: String): KernelEndpoint {
        val result = withContext(Dispatchers.IO) {
            val body = AiStudioProtocol.formEncode(
                mapOf(
                    "projectId" to projectId,
                    "versionId" to "0",
                    "scheduleName" to scheduleName.ifBlank { AiStudioProtocol.DEFAULT_SCHEDULE },
                    "startMode" to AiStudioProtocol.START_MODE_NOTEBOOK.toString(),
                ),
            )
            val request = Request.Builder()
                .url(AiStudioProtocol.BASE_URL + AiStudioProtocol.PATH_RUNNING_STATUS_CHECK)
                .header("Cookie", account.cookie)
                .header("x-requested-with", "XMLHttpRequest")
                .header("Referer", AiStudioProtocol.BASE_URL + "/")
                .apply {
                    if (account.bdToken.isNotBlank()) header("x-studio-token", account.bdToken)
                    val xsrf = AiStudioProtocol.cookieValue(account.cookie, "_xsrf")
                    if (xsrf.isNotBlank()) header("X-XSRFToken", xsrf)
                }
                .post(body.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType()))
                .build()
            val raw = client.newCall(request).execute().use { it.body?.string().orEmpty() }
            AiStudioProtocol.unwrap(raw, "查询内核环境")
        }
        val baseUrl = result.optString("baseUrl")
        val hub = result.optString("hubBaseUrl")
        AppLogger.info("内核通道：baseUrl=$baseUrl hubBaseUrl=$hub")
        return KernelEndpoint(
            baseUrl = baseUrl,
            basePath = baseUrl.substringAfter(".com", "").ifBlank { baseUrl },
            token = result.optString("token"),
            hubBaseUrl = hub,
            hubPath = hub.substringAfter(".com", "").ifBlank { hub },
        )
    }

    /**
     * 列出运行中的内核。
     *
     * 前端：`GET {hubBaseUrl}/api/kernels`，header 带 `auth: <token>`。
     * 返回数组，每项含 id / name / execution_state。
     */
    suspend fun listKernels(
        account: AiStudioAccount,
        endpoint: KernelEndpoint,
    ): List<KernelInfo> = withContext(Dispatchers.IO) {
        val url = kernelBase(endpoint) + "/api/kernels"
        val raw = get(account, endpoint, url, "读取内核列表")
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull()
            ?: (runCatching { JSONObject(raw).optJSONArray("result") }.getOrNull())
            ?: return@withContext emptyList()
        buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                val id = item.optString("id")
                if (id.isBlank()) return@repeat
                add(
                    KernelInfo(
                        id = id,
                        name = item.optString("name"),
                        state = item.optString("execution_state"),
                    ),
                )
            }
        }
    }

    /** 新建内核：`POST {hub}/api/kernels`（带 name）。 */
    suspend fun startKernel(
        account: AiStudioAccount,
        endpoint: KernelEndpoint,
        kernelName: String = "python3",
    ): KernelInfo = withContext(Dispatchers.IO) {
        val url = kernelBase(endpoint) + "/api/kernels"
        val body = JSONObject().put("name", kernelName).toString()
        val raw = post(account, endpoint, url, body, "新建内核")
        val item = runCatching { JSONObject(raw) }.getOrNull() ?: throw AiStudioException("新建内核失败：返回不是对象")
        KernelInfo(
            id = item.optString("id"),
            name = item.optString("name"),
            state = item.optString("execution_state"),
        )
    }

    /**
     * 内核接口主机。
     *
     * hubBaseUrl 可能是完整 URL，也可能只有路径（前端做过 split(".com")）。
     * 完整 URL 直接用；只有路径时补平台域名。
     */
    private fun kernelBase(endpoint: KernelEndpoint): String {
        val raw = endpoint.hubBaseUrl.ifBlank { endpoint.hubPath }
        if (raw.isBlank()) throw AiStudioException("内核通道失败：平台没有返回 hubBaseUrl")
        return if (raw.startsWith("http")) raw.trimEnd('/')
        else AiStudioProtocol.BASE_URL + "/" + raw.trim('/')
    }

    private fun commonHeaders(account: AiStudioAccount, endpoint: KernelEndpoint): Map<String, String> = buildMap {
        put("Cookie", account.cookie)
        put("x-requested-with", "XMLHttpRequest")
        put("Referer", AiStudioProtocol.BASE_URL + "/")
        // 前端就是靠这个 auth 头过内核接口的鉴权。
        put("auth", endpoint.token)
        if (account.bdToken.isNotBlank()) put("x-studio-token", account.bdToken)
        val xsrf = AiStudioProtocol.cookieValue(account.cookie, "_xsrf")
        if (xsrf.isNotBlank()) put("X-XSRFToken", xsrf)
    }

    private fun get(
        account: AiStudioAccount,
        endpoint: KernelEndpoint,
        url: String,
        action: String,
    ): String {
        val builder = Request.Builder().url(url)
        commonHeaders(account, endpoint).forEach { (k, v) -> builder.header(k, v) }
        val response = try {
            client.newCall(builder.get().build()).execute()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw AiStudioException("${action}失败：网络不可达（${error.message.orEmpty()}）")
        }
        return response.use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw AiStudioException("${action}失败：HTTP ${resp.code}（${body.take(120)}）")
            body
        }
    }

    private fun post(
        account: AiStudioAccount,
        endpoint: KernelEndpoint,
        url: String,
        json: String,
        action: String,
    ): String {
        val builder = Request.Builder().url(url)
        commonHeaders(account, endpoint).forEach { (k, v) -> builder.header(k, v) }
        val response = try {
            client.newCall(builder.post(json.toRequestBody(jsonMedia)).build()).execute()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw AiStudioException("${action}失败：网络不可达（${error.message.orEmpty()}）")
        }
        return response.use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw AiStudioException("${action}失败：HTTP ${resp.code}（${body.take(120)}）")
            body
        }
    }

    data class KernelInfo(val id: String, val name: String, val state: String) {
        fun displayName(): String = when {
            name.isNotBlank() && state.isNotBlank() -> "$name · $state"
            name.isNotBlank() -> name
            state.isNotBlank() -> "$id · $state"
            else -> id
        }
    }
}
