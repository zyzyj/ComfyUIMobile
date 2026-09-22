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
 * BML Codelab（JupyterLab）的终端通道。
 *
 * v0.2.0：控制台改成**真终端**——输入命令、看到回显，用来启动 ComfyUI 等。
 *
 * 链路（全部经真机实测 + 前端 bundle 核实）：
 *  1. 先进 notebook：`POST /studio/project/notebook/enter`（不调它 IDE 环境不会分配）。
 *  2. 取环境地址：`GET /studio/project/envs/baseinfo?projectId=...`
 *     → `result = {baseUrl, token, hubBaseUrl}`。
 *  3. 终端接口走 **https + hub 路径** `{hubBaseUrl}api/terminals`：
 *     - REST：`POST api/terminals` 新建终端（返回 `{name}`）；
 *     - WS：`terminals/websocket/{name}` 收发（terminado 数组协议）。
 *
 * 两个真机踩过的坑：
 *  - baseinfo 给的 baseUrl 是 **http 用户路径**，直接用会被 301→https、再 302→hub 路径，
 *    最终落到登录页。必须直接走 https + hub 路径。
 *  - 项目未运行时 baseinfo 会回 `baseUrl: "...null"`，必须当成无效值。
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
        /** baseinfo 给的 baseUrl（用户路径，http）。 */
        val baseUrl: String,
        /** 仅路径部分。 */
        val basePath: String,
        val token: String,
        /** baseinfo 给的 hubBaseUrl（hub 路径）——终端接口真正要用的是它。 */
        val hubBaseUrl: String = "",
    ) {
        fun isUsable(): Boolean = baseUrl.isNotBlank() && token.isNotBlank()
    }

    /**
     * 拉取环境连接信息。
     *
     * 真正管用的是 `GET /studio/project/envs/baseinfo?projectId=...`（前端
     * loadNotebookConfig 用的就是它），result = {baseUrl, token, hubBaseUrl}。
     * 以前用的 `POST /studio/project/running_status_check` 在项目已运行时**不回
     * baseUrl**（真机实测 baseUrl 为空），所以才连不上终端；这里改成主用 baseinfo、
     * 拿不到再退回 running_status_check。
     */
    suspend fun fetchEndpoint(account: AiStudioAccount, projectId: String, scheduleName: String): KernelEndpoint {
        seedCookies(account.cookie)
        val result = withContext(Dispatchers.IO) {
            // 前端进 notebook 前会先调 enter，它才真正把 IDE 环境拉起来；
            // 不先 enter 的话 baseinfo 会回 `baseUrl: "...null"`（环境未分配）。
            runCatching { enterNotebook(account, projectId) }
                .onFailure { AppLogger.warn("notebook/enter 失败（继续尝试取环境信息）", it) }
            val info = runCatching { fetchBaseInfo(account, projectId) }.getOrNull()
            if (info != null && validBaseUrl(info.optString("baseUrl")) != null) return@withContext info
            // baseinfo 在实例刚起来时会短暂返回空（真机日志里 6 次），直接兜底容易
            // 拿到还没分配完的信息。先重试一次再走兜底。
            delay(1_500)
            val retry = runCatching { fetchBaseInfo(account, projectId) }.getOrNull()
            if (retry != null && validBaseUrl(retry.optString("baseUrl")) != null) return@withContext retry
            AppLogger.warn("envs/baseinfo 未返回有效 baseUrl，改用 running_status_check 兜底")
            fetchRunningStatusCheck(account, projectId, scheduleName)
        }
        val baseUrl = validBaseUrl(result.optString("baseUrl"))
        AppLogger.info("终端通道：baseUrl=$baseUrl")
        return KernelEndpoint(
            baseUrl = baseUrl.orEmpty(),
            basePath = baseUrl?.substringAfter(".com", "")?.ifBlank { baseUrl } ?: "",
            token = result.optString("token"),
            hubBaseUrl = result.optString("hubBaseUrl"),
        )
    }

    /**
     * 轻量探测环境是否已就绪（**不**调 notebook/enter）。
     *
     * 轮询期间每几秒跑一次，若用 [fetchEndpoint] 会顺带下发 enter + 重试，既加重平台
     * 负担也刷屏日志（真机一轮启动就多出十几条）。这里只问一次 baseinfo，够判断了。
     */
    suspend fun peekEndpoint(account: AiStudioAccount, projectId: String): KernelEndpoint? {
        seedCookies(account.cookie)
        return withContext(Dispatchers.IO) {
            val info = runCatching { fetchBaseInfo(account, projectId) }.getOrNull() ?: return@withContext null
            val baseUrl = validBaseUrl(info.optString("baseUrl")) ?: return@withContext null
            KernelEndpoint(
                baseUrl = baseUrl,
                basePath = baseUrl.substringAfter(".com", "").ifBlank { baseUrl },
                token = info.optString("token"),
                hubBaseUrl = info.optString("hubBaseUrl"),
            ).takeIf { it.isUsable() }
        }
    }

    /**
     * 平台有时把 baseUrl 拼成 `http://aistudio.baidu.comnull`（环境还没分配）。
     * 这种值拿去解析会得到 `aistudio.baidu.comnull` 这种域名，必须当成无效。
     */
    private fun validBaseUrl(raw: String): String? {
        if (raw.isBlank() || raw.endsWith("null") || !raw.contains("/user/")) return null
        return raw
    }

    /** `POST /studio/project/notebook/enter`：进入 notebook，触发 IDE 环境分配。 */
    private fun enterNotebook(account: AiStudioAccount, projectId: String): String {
        val body = AiStudioProtocol.formEncode(mapOf("projectId" to projectId))
        val request = Request.Builder()
            .url(AiStudioProtocol.BASE_URL + AiStudioProtocol.PATH_NOTEBOOK_ENTER)
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
        AppLogger.info("进入 notebook 响应：${raw.take(300)}")
        return raw
    }

    /** `GET /studio/project/envs/baseinfo?projectId=...` → result 里带 baseUrl/token。 */
    private fun fetchBaseInfo(account: AiStudioAccount, projectId: String): JSONObject {
        val url = AiStudioProtocol.BASE_URL + AiStudioProtocol.PATH_ENV_BASEINFO +
            "?projectId=" + URLEncoder.encode(projectId, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("x-requested-with", "XMLHttpRequest")
            .header("Referer", AiStudioProtocol.BASE_URL + "/")
            .apply {
                if (account.bdToken.isNotBlank()) header("x-studio-token", account.bdToken)
                val xsrf = AiStudioProtocol.cookieValue(account.cookie, "_xsrf")
                if (xsrf.isNotBlank()) header("X-XSRFToken", xsrf)
            }
            .get()
            .build()
        val raw = client.newCall(request).execute().use { it.body?.string().orEmpty() }
        AppLogger.info("环境信息响应：${raw.take(300)}")
        return AiStudioProtocol.unwrap(raw, "查询环境信息")
    }

    private fun fetchRunningStatusCheck(
        account: AiStudioAccount,
        projectId: String,
        scheduleName: String,
    ): JSONObject {
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
        AppLogger.info("运行状态响应：${raw.take(300)}")
        return AiStudioProtocol.unwrap(raw, "查询内核环境")
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
     * Jupyter 终端的 WebSocket 协议是 **JSON 数组**（terminado，不是内核那套 dict 消息）：
     * 发 `["stdin", "命令\r"]`，收 `["stdout", "输出"]`，改尺寸用 `["set_size", rows, cols]`。
     * 以前发的是 `{"type":"stdin",...}`，服务器当成畸形消息直接把连接掉了——
     * 现象就是“刚连上、一输命令就断开”。
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
                    val raw = AiStudioProtocol.parseTerminalOutput(text) ?: return
                    // 剥掉 ANSI 控制序列与不可见控制字符，否则界面上是一堆乱码。
                    val clean = AiStudioProtocol.stripControlChars(AiStudioProtocol.stripAnsi(raw))
                    if (clean.isNotEmpty()) onOutput(clean)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // 只有「当前 socket」的回调才算数。换连接时 closeTerminal() 会先关掉旧
                    // socket，它的 onFailure/onClosed 是异步后到的——不判断身份的话，旧连接
                    // 的回调会把刚建立的新连接报成"断开"，于是界面一直"重连中"。
                    if (terminalSocket !== webSocket) return
                    terminalSocket = null
                    onClosed(t.message ?: "连接中断")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (terminalSocket !== webSocket) return
                    terminalSocket = null
                    onClosed("终端已关闭（$code）")
                }
            },
        )
    }

    /** 向终端发一条命令（自动补回车）。协议帧：`["stdin", "...\r"]`。 */
    fun sendInput(command: String): Boolean {
        val socket = terminalSocket ?: return false
        return socket.send(AiStudioProtocol.terminalStdinFrame(command))
    }

    /**
     * 向终端发送原始按键（不补回车）。
     *
     * 用于 Ctrl+C（`\u0003`）这类控制字符——它们是裸字节，补上 `\r` 反而会被当成
     * 回车提交。协议帧同样是 `["stdin", "..."]`，只是内容不带 `\r`。
     */
    fun sendRawInput(raw: String): Boolean {
        val socket = terminalSocket ?: return false
        return socket.send(AiStudioProtocol.terminalRawStdinFrame(raw))
    }

    /** 告知终端窗口尺寸，避免输出错行。协议帧：`["set_size", rows, cols]`（注意顺序）。 */
    fun resize(cols: Int, rows: Int) {
        val socket = terminalSocket ?: return
        socket.send(AiStudioProtocol.terminalResizeFrame(rows, cols))
    }

    fun closeTerminal() {
        terminalSocket?.close(1000, "client close")
        terminalSocket = null
    }

    /**
     * 导出 CookieJar 里当前的 Cookie 串（`name=value; name2=value2`）。
     *
     * 连终端的过程中，AI Studio 网关（nginx）会下发**项目级** Cookie——`ide-proxy`
     * 和 `user-{uid}-{pid}`——它们才是 `api_serving` 反代（ComfyUI 地址）真正校验的东西。
     * 只带账号 Cookie（BDUSS 等）会被 302 回登录页；这也是为什么以前“手动连接
     * ComfyUI 要手粘 Cookie”。这里把网关下发的这份导出给 ComfyUI 连接复用。
     */
    fun exportCookies(): String = cookieStore.values
        .asSequence()
        .flatten()
        .distinctBy { it.name }
        .joinToString("; ") { "${it.name}=${it.value}" }

    /**
     * 当前已捕获的 Cookie **名字**（不含值）。
     *
     * 仅用于诊断：判断网关是否下发了项目级 Cookie（`ide-proxy`、`user-*`）。
     * 值等同账号密码，绝不写进日志。
     */
    fun cookieNames(): List<String> = cookieStore.values
        .asSequence()
        .flatten()
        .map { it.name }
        .distinct()
        .sorted()
        .toList()

    /** 是否已拿到 api_serving 反代鉴权必需的项目级 Cookie。 */
    fun hasProjectCookies(): Boolean {
        val names = cookieNames()
        return names.contains("ide-proxy") || names.any { it.startsWith("user-") }
    }

    /**
     * 预热项目 Cookie：像浏览器那样访问一次 Codelab 环境首页（用户路径）。
     *
     * 网关在访问 `/user/{uid}/{pid}/` 时会下发项目级 Cookie（`ide-proxy`、
     * `user-{uid}-{pid}`）。这些是 `api_serving` 反代鉴权所必需、而账号 Cookie 里没有的。
     * 平台网页能直连 api_serving 正是因为浏览器访问过 Codelab 页、拿过这两段。
     * 这里补上同一动作，让 CookieJar 拿到它们。失败不影响终端本身。
     */
    suspend fun warmUpProjectCookies(account: AiStudioAccount, endpoint: KernelEndpoint) {
        withContext(Dispatchers.IO) {
            runCatching {
                // 先访问项目根（Codelab 首页），再补一次项目详情页：两处都可能 Set-Cookie。
                val targets = buildList {
                    add(withToken(endpoint, userBase(endpoint)))
                    add(withToken(endpoint, userBase(endpoint) + "home"))
                    add(withToken(endpoint, userBase(endpoint) + "lab"))
                    add(withToken(endpoint, userBase(endpoint) + "tree"))
                }
                targets.forEach { url ->
                    runCatching {
                        val builder = Request.Builder().url(url)
                        commonHeaders(account, endpoint).forEach { (k, v) -> builder.header(k, v) }
                        client.newCall(builder.get().build()).execute().use { response ->
                            AppLogger.info("Codelab 预热：${response.code} ${response.request.url.encodedPath}")
                        }
                    }
                }
                // 只记名字，不记值（值等同凭据）。
                AppLogger.info("Codelab 预热完成：已捕获 Cookie=[${cookieNames().joinToString()}]")
                if (!hasProjectCookies()) {
                    AppLogger.warn("Codelab 预热未拿到项目级 Cookie（ide-proxy / user-*），ComfyUI 反代可能仍被拒")
                }
            }.onFailure { error ->
                AppLogger.warn("Codelab 预热失败（不影响终端，可能影响 ComfyUI 自动连接）", error)
            }
        }
    }

    /**
     * 终端接口主机：**https + 用户路径** `https://aistudio.baidu.com/{zone}/user/{uid}/{pid}/`。
     *
     * 真机逐条验证：https + 用户路径下 GET/POST `api/terminals` 都 200（v0.2.0 实测就是
     * 这个，终端能建起来）；hub 路径 GET 靠 302 跳回用户路径能通，但 POST 被网关回 405。
     * baseinfo 给的 baseUrl 是 http（running_status_check 给的是 https），http 会被
     * 301→https，所以这里强制升级成 https。
     */
    private fun userBase(endpoint: KernelEndpoint): String {
        val raw = endpoint.baseUrl.ifBlank { endpoint.hubBaseUrl.ifBlank { endpoint.basePath } }
        if (raw.isBlank()) throw AiStudioException("终端通道失败：平台没有返回环境地址")
        val absolute = if (raw.startsWith("http")) raw else AiStudioProtocol.BASE_URL + "/" + raw.trim('/')
        // 平台只接受 https；baseinfo 给的地址是 http，这里强制升级。
        val secured = when {
            absolute.startsWith("http://") -> "https://" + absolute.removePrefix("http://")
            else -> absolute
        }
        return secured.trimEnd('/') + "/"
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
