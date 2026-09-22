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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * BML Codelab（JupyterLab 3.0）的终端通道。
 *
 * v0.2.0：控制台改成**真终端**——输入命令、看到回显，用来启动 ComfyUI 等。
 *
 * 链路（从前端 bundle 与平台响应核实）：
 *  1. `POST /studio/project/running_status_check` → `result = {baseUrl, token, hubBaseUrl}`。
 *  2. **所有 Jupyter 接口都挂在用户路径 baseUrl 下**（形如
 *     `https://aistudio.baidu.com/bj-cpu-01/user/{uid}/{pid}/`）：
 *     - REST：`POST {baseUrl}api/terminals` 新建终端；
 *     - WS：`{wsBase}terminals/websocket/{name}` 收发终端输入输出。
 *
 * 特别注意 hubBaseUrl（`.../hub/user/...`）那条路：GET `/api/kernels` 能通，
 * 但 POST 会被平台网关回 405——所以内核/终端一律走用户路径 baseUrl。
 */
class AiStudioKernelClient {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * Jupyter 自己的会话 cookie 必须留得住。
     *
     * 光带 AI Studio 的 Cookie 头不够：Jupyter 会在首次请求时 Set-Cookie 自己的
     * `_xsrf` 与 session，后续 POST 要拿它做 CSRF 校验。以前没有 CookieJar，
     * 这个 cookie 直接被丢掉，POST 就被网关重定向到登录页（返回 200 + HTML，
     * 看着像“成功但没 name”）。这里既存住服务端下发的 cookie，也把账号 Cookie
     * 预先种进 store，让每个请求都带齐。
     */
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
    private var seededCookie = ""

    private fun seedCookies(rawCookie: String) {
        if (rawCookie.isBlank() || rawCookie == seededCookie) return
        seededCookie = rawCookie
        val url = AiStudioProtocol.BASE_URL.toHttpUrlOrNull() ?: return
        val cookies = rawCookie.split(';').mapNotNull { pair ->
            val name = pair.substringBefore('=', "").trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isBlank()) null
            else Cookie.Builder().name(name).value(value).domain(url.host).path("/").build()
        }
        cookieStore[url.host] = cookies.toMutableList()
    }

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = cookieStore.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { fresh ->
                list.removeAll { it.name == fresh.name }
                list.add(fresh)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookieStore[url.host].orEmpty().filter { it.matches(url) }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    private var terminalSocket: WebSocket? = null

    /** 平台返回的环境连接信息。 */
    data class KernelEndpoint(
        /** 完整 baseUrl（含域名），形如 `https://.../user/{uid}/{pid}/`，Jupyter 接口的根。 */
        val baseUrl: String,
        /** 仅路径部分（前端就是用它拼 kernels 的）。 */
        val basePath: String,
        val token: String,
    ) {
        fun isUsable(): Boolean = baseUrl.isNotBlank() && token.isNotBlank()
    }

    /**
     * 拉取环境连接信息。
     *
     * 平台在 `running_status_check` 的 result 里回 baseUrl/token/hubBaseUrl。
     * 前端会按 `.com` 切分只留路径——这里两种形态都保留，避免平台换域名后缀时拼错 URL。
     */
    suspend fun fetchEndpoint(account: AiStudioAccount, projectId: String, scheduleName: String): KernelEndpoint {
        seedCookies(account.cookie)
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
        AppLogger.info("终端通道：baseUrl=$baseUrl")
        return KernelEndpoint(
            baseUrl = baseUrl,
            basePath = baseUrl.substringAfter(".com", "").ifBlank { baseUrl },
            token = result.optString("token"),
        )
    }

    /**
     * 列出现有终端。`GET {baseUrl}api/terminals` → `[{name}]`。
     *
     * 项目重启后旧终端会消失，所以连之前先探一下，有就复用。
     */
    suspend fun listTerminals(
        account: AiStudioAccount,
        endpoint: KernelEndpoint,
    ): List<String> = withContext(Dispatchers.IO) {
        val url = withToken(endpoint, userBase(endpoint) + "api/terminals")
        val raw = get(account, endpoint, url, "读取终端列表")
        AppLogger.info("终端列表响应：${raw.take(300)}")
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return@withContext emptyList()
        buildList {
            repeat(array.length()) { index ->
                val name = array.optJSONObject(index)?.optString("name").orEmpty()
                if (name.isNotBlank()) add(name)
            }
        }
    }

    /** 新建终端：`POST {baseUrl}api/terminals` → `{name}`。 */
    suspend fun createTerminal(
        account: AiStudioAccount,
        endpoint: KernelEndpoint,
    ): String = withContext(Dispatchers.IO) {
        val url = withToken(endpoint, userBase(endpoint) + "api/terminals")
        val raw = post(account, endpoint, url, "{}", "新建终端")
        AppLogger.info("新建终端响应：${raw.take(300)}")
        val name = runCatching { JSONObject(raw).optString("name") }.getOrNull().orEmpty()
        if (name.isBlank()) throw AiStudioException("新建终端失败：响应里没有 name（${raw.take(160)}）")
        name
    }

    /**
     * 打开终端的 WebSocket，开始收发命令。
     *
     * Jupyter 终端协议：发 `{"type":"stdin","content":"..."}`，
     * 收 `{"type":"stdout","content":"..."}`；另有 `resize` 告知窗口尺寸。
     */
    fun openTerminal(
        account: AiStudioAccount,
        endpoint: KernelEndpoint,
        name: String,
        onOutput: (String) -> Unit,
        onOpen: () -> Unit,
        onClosed: (String) -> Unit,
    ) {
        closeTerminal()
        val url = withToken(endpoint, wsBase(endpoint) + "terminals/websocket/" + encode(name))
        val builder = Request.Builder().url(url)
        commonHeaders(account, endpoint).forEach { (k, v) -> builder.header(k, v) }
        terminalSocket = client.newWebSocket(
            builder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                    if (json.optString("type") != "stdout") return
                    json.optString("content").takeIf { it.isNotEmpty() }?.let(onOutput)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (terminalSocket === webSocket) terminalSocket = null
                    onClosed(t.message ?: "连接中断")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (terminalSocket === webSocket) terminalSocket = null
                    onClosed("终端已关闭（$code）")
                }
            },
        )
    }

    /** 向终端发一条命令（自动补回车）。 */
    fun sendInput(command: String): Boolean {
        val socket = terminalSocket ?: return false
        val payload = JSONObject().put("type", "stdin").put("content", command + "\r")
        return socket.send(payload.toString())
    }

    /** 告知终端窗口尺寸，避免输出错行。 */
    fun resize(cols: Int, rows: Int) {
        val socket = terminalSocket ?: return
        socket.send(JSONObject().put("type", "resize").put("cols", cols).put("rows", rows).toString())
    }

    fun closeTerminal() {
        terminalSocket?.close(1000, "client close")
        terminalSocket = null
    }

    /**
     * 用户路径主机：`https://aistudio.baidu.com/{zone}/user/{uid}/{pid}/`。
     *
     * Jupyter 的 REST/WS 全挂在这条路径下（前端 2299.js 就是 `url_path_join(base_url, ...)`）。
     * 不能改用 hubBaseUrl：那条路的网关只放行 GET，POST 会 405。
     */
    private fun userBase(endpoint: KernelEndpoint): String {
        val raw = endpoint.baseUrl.ifBlank { endpoint.basePath }
        if (raw.isBlank()) throw AiStudioException("终端通道失败：平台没有返回 baseUrl")
        val absolute = if (raw.startsWith("http")) raw else AiStudioProtocol.BASE_URL + "/" + raw.trim('/')
        return absolute.trimEnd('/') + "/"
    }

    private fun wsBase(endpoint: KernelEndpoint): String {
        val http = userBase(endpoint)
        return when {
            http.startsWith("https") -> "wss" + http.substring("https".length)
            http.startsWith("http") -> "ws" + http.substring("http".length)
            else -> http
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * 给 URL 补上 Jupyter 的 token 查询参数。
     *
     * 平台网关会吃掉自定义头（`auth`），而 Jupyter 自身认 `?token=`；两头都带才稳。
     */
    private fun withToken(endpoint: KernelEndpoint, url: String): String {
        if (endpoint.token.isBlank()) return url
        val separator = if (url.contains('?')) "&" else "?"
        return url + separator + "token=" + encode(endpoint.token)
    }

    private fun commonHeaders(account: AiStudioAccount, endpoint: KernelEndpoint): Map<String, String> = buildMap {
        // Cookie 由 CookieJar 统一带上（含 Jupyter 下发的 _xsrf），不再手写。
        put("x-requested-with", "XMLHttpRequest")
        put("Referer", AiStudioProtocol.BASE_URL + "/")
        // 平台网关认 `auth` 头；Jupyter 自身认标准 `Authorization: token`。两个都带。
        put("auth", endpoint.token)
        if (endpoint.token.isNotBlank()) put("Authorization", "token " + endpoint.token)
        if (account.bdToken.isNotBlank()) put("x-studio-token", account.bdToken)
        // _xsrf 以 CookieJar 里最新的为准（Jupyter 会下发自己的），拿不到才退回账号 Cookie。
        val xsrf = currentXsrf() ?: AiStudioProtocol.cookieValue(account.cookie, "_xsrf")
        if (!xsrf.isNullOrBlank()) put("X-XSRFToken", xsrf)
    }

    private fun currentXsrf(): String? = cookieStore.values
        .asSequence()
        .flatten()
        .firstOrNull { it.name == "_xsrf" }
        ?.value

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
            val redirected = resp.priorResponse?.let { "（经重定向 ${it.code} → ${resp.request.url}）" }.orEmpty()
            AppLogger.info("${action}：HTTP ${resp.code} ${resp.header("Content-Type").orEmpty()}$redirected")
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
            val redirected = resp.priorResponse?.let { "（经重定向 ${it.code} → ${resp.request.url}）" }.orEmpty()
            AppLogger.info("${action}：HTTP ${resp.code} ${resp.header("Content-Type").orEmpty()}$redirected")
            body
        }
    }
}
