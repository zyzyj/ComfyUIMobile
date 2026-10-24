package com.local.comfyuimobile.network

import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.model.AiStudioAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.util.UUID
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
    /**
     * Cookie 存储。
     *
     * 并发读写方：IO 线程（seedCookies / 预热请求）、OkHttp 网络线程
     * （saveFromResponse / loadForRequest）、调用方线程（exportCookies /
     * cookieNames / currentXsrf）。以前用普通 mutableMap，并发下可能抛
     * ConcurrentModificationException 或读到半更新列表（表现为偶发"连上又断"）。
     * 换 ConcurrentHashMap；列表内元素本身也只在锁内改。
     */
    private val cookieStore = java.util.concurrent.ConcurrentHashMap<String, MutableList<Cookie>>()
    private val cookieLock = Any()

    @Volatile
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
        synchronized(cookieLock) {
            // 按 name 合并而不是整表替换：服务端下发的 Jupyter 会话 cookie
            // （_xsrf 等）不能因为重新 seed 账号 Cookie 而被丢掉。
            val list = cookieStore.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { fresh ->
                list.removeAll { it.name == fresh.name }
                list.add(fresh)
            }
        }
    }

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(cookieLock) {
                val list = cookieStore.getOrPut(url.host) { mutableListOf() }
                cookies.forEach { fresh ->
                    list.removeAll { it.name == fresh.name }
                    list.add(fresh)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookieLock) {
            cookieStore[url.host].orEmpty().filter { it.matches(url) }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // v0.2.43：心跳从 20 秒放宽到 30 秒。OkHttp 给 pong 的宽限就等于 ping 间隔，
        // 而 App 切后台时系统会节流网络、反代又爱抬长连接，20 秒很容易来不及——
        // 日志里"sent ping but didn't receive pong within 20000ms"反复出现，
        // 终端就这么断开。放宽到 30 秒给 pong 留出余量（断开后已有持续重连兜底）。
        .pingInterval(30, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    /**
     * 令牌刷新器与回写回调（v0.2.81）。
     *
     * 与 [AiStudioClient] 共用同一个实例：底层是同一个 bdToken，写两份只会
     * 各自拿一份“更新后”的令牌，反而互相覆盖（“平行路径只改一条”的反面教训）。
     * 这里只用于平台网关（enter / baseinfo / running_status_check），终端自身的
     * Jupyter 会话走 endpoint.token，与本头无关。
     */
    var tokenRefresher: AiStudioTokenRefresher? = null
    var onAccountRefreshed: ((AiStudioAccount) -> AiStudioAccount)? = null

    /**
     * 调平台网关；遇 403（令牌过期）时刷新令牌并重试一次。
     *
     * 网关方法拿到的是“账号 + bdToken 头”，所以刷新后把新账号传给 block 即可。
     */
    private suspend fun <T> gatewayCall(
        account: AiStudioAccount,
        action: String,
        block: suspend (AiStudioAccount) -> T,
    ): T {
        try {
            return block(account)
        } catch (error: AiStudioException) {
            if (error is CancellationException) throw error
            val refresher = tokenRefresher
            if (error.errorCode != 403 || refresher == null) throw error
            when (val refreshed = refresher.refresh(account, account.bdToken)) {
                is AiStudioTokenRefresher.Result.Refreshed -> {
                    val updated = onAccountRefreshed?.invoke(refreshed.account) ?: refreshed.account
                    return block(updated)
                }
                is AiStudioTokenRefresher.Result.LoggedOut ->
                    throw AiStudioException("${action}失败：登录已失效，请到「账号」页重新登录 AI Studio", 403)
                is AiStudioTokenRefresher.Result.ExtractionFailed ->
                    throw AiStudioException("${action}失败：${refreshed.message}", 403)
                is AiStudioTokenRefresher.Result.Unreachable ->
                    throw AiStudioException("${action}失败：刷新平台令牌时网络不可达（${refreshed.message}）", 403)
            }
        }
    }

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
     * 执行一个平台请求并取回 body（v0.3.10）。
     *
     * 抽出来是为了让 enter / baseinfo / running_status 这三个**裸调用**也走同一套
     * 登录墙判定——它们以前各写一行 `newCall(...).execute()`，Cookie 过期时拿到
     * 的是登录页 HTML，随后 `unwrap()` 解析失败报一句含糊的错（真机日志里就是这样
     * 被误诊成接口故障的）。
     *
     * 不走 get/post 是因为这三个请求各自带不同 header 与参数形态，但它们对
     * **登录墙**的判断应该完全一致——不能只有一个入口判、别的入口不判。
     */
    private fun executeForBody(request: Request, action: String): String {
        val response = try {
            client.newCall(request).execute()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw AiStudioException("${action}失败：网络不可达（${error.message.orEmpty()}）")
        }
        return response.use { resp ->
            val body = resp.body?.string().orEmpty()
            ensureNotLoginWall(resp, body, action)
            body
        }
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
            // baseinfo 在环境刚起来/正在回收时会短暂返回 `...null`（真机日志里反复出现）。
            // 以前只重试 1 次就走兜底，而兜底的 running_status_check 根本不回 baseUrl，
            // 于是环境明明存在也报“平台没有返回环境地址”。改成多次退避重试。
            repeat(4) { attempt ->
                if (attempt > 0) delay(1_500L)
                val info = runCatching { fetchBaseInfo(account, projectId) }.getOrNull()
                if (info != null && validBaseUrl(info.optString("baseUrl")) != null) {
                    return@withContext info
                }
            }
            AppLogger.warn("envs/baseinfo 连续未返回有效 baseUrl，改用 running_status_check 兜底")
            val fallback = fetchRunningStatusCheck(account, projectId, scheduleName)
            // v0.3.9（真机反馈）：拿不到地址必须**说清是哪种拿不到**。
            //
            // 真机日志（19:20:52 点连接终端）分两段：
            //   19:20:58 `{"running":true, "baseUrl":""}` → 报"平台没有返回环境地址"
            //   19:21:15 `{"running":true, "baseUrl":""}` → 同样失败
            //   19:21:26 列表刷新才发现项目已从运行变为停止
            //
            // 注意：**连接失败那一瞬 running 是 true**——所以不能只看 running:false
            // 才报错。两种情形都抛本异常，用 [AiStudioEnvironmentGoneException.recycled]
            // 区分，文案与后续动作不同：
            //   已停 → "必须重新启动项目"（重试无意义）；
            //   仍说在跑 → "很可能正在被回收，先刷新确认项目状态"（别抱期望重试）。
            val gone = AiStudioProtocol.environmentGone(fallback)
            throw AiStudioEnvironmentGoneException(
                if (gone) {
                    "项目环境已被平台回收（项目已停止）：无法建立终端。请先重新启动项目环境。"
                } else {
                    "平台没能给出终端地址（项目仍显示运行中，很可能正在被回收）。" +
                        "请刷新确认项目状态；若已停止，重新启动后再连终端。"
                },
                recycled = gone,
            )
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
    private suspend fun enterNotebook(account: AiStudioAccount, projectId: String): String =
        gatewayCall(account, "进入 notebook") { acct -> enterNotebookOnce(acct, projectId) }

    private fun enterNotebookOnce(account: AiStudioAccount, projectId: String): String {
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
        val raw = executeForBody(request, "进入 notebook")
        AppLogger.info("进入 notebook 响应：${raw.take(300)}")
        return raw
    }

    /** `GET /studio/project/envs/baseinfo?projectId=...` → result 里带 baseUrl/token。 */
    private suspend fun fetchBaseInfo(account: AiStudioAccount, projectId: String): JSONObject =
        gatewayCall(account, "查询环境信息") { acct -> fetchBaseInfoOnce(acct, projectId) }

    private fun fetchBaseInfoOnce(account: AiStudioAccount, projectId: String): JSONObject {
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
        val raw = executeForBody(request, "查询环境信息")
        AppLogger.info("环境信息响应：${AiStudioProtocol.redactSecrets(raw).take(300)}")
        return AiStudioProtocol.unwrap(raw, "查询环境信息")
    }

    private suspend fun fetchRunningStatusCheck(
        account: AiStudioAccount,
        projectId: String,
        scheduleName: String,
    ): JSONObject = gatewayCall(account, "查询内核环境") { acct ->
        fetchRunningStatusCheckOnce(acct, projectId, scheduleName)
    }

    private fun fetchRunningStatusCheckOnce(
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
        val raw = executeForBody(request, "查询内核环境")
        AppLogger.info("运行状态响应：${AiStudioProtocol.redactSecrets(raw).take(300)}")
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
     * 一个终端会话（v0.2.97）。
     *
     * 为什么要有这个东西：以前 `terminalSocket` / `terminalConnectionId` /
     * `terminalPendingEscape` 都是单变量，只能容下一个终端。但 AI 与终端交互的前提
     * 恰恰是**多个终端**：一个跑 ComfyUI（日志一直在刷），另一个跑临时命令要拿到
     * 干净输出——共用一条的话，"输入一条命令拿对应输出"这个前提直接不成立。
     *
     * 每会话自带：自己那条 socket、自己的代次 id、自己的半截转义缓冲。
     * 平台侧本来就支持多终端（`api/terminals` 可建多个，每个独立 name + 独立 WS）。
     */
    class TerminalSession internal constructor(
        val name: String,
        internal val connectionId: String,
        socket: WebSocket?,
    ) {
        /**
         * 底层 socket。
         *
         * 先建会话、后拿 socket（`newWebSocket` 是同步返回的，但回调里要引用会话），
         * 所以用可写字段而不是构造参数——写成 `lateinit` 的局部变量再回填容易在
         * 回调早于赋值时炸掉。
         */
        @Volatile internal var socket: WebSocket? = socket
            private set

        /** 上一帧结尾处未写完的转义序列，拼到下一帧前面再处理。**每会话一份**。 */
        internal val pendingEscape = StringBuilder()

        @Volatile internal var closed: Boolean = false

        internal fun attach(webSocket: WebSocket) {
            socket = webSocket
        }

        internal fun send(frame: String): Boolean {
            val target = socket ?: return false
            return !closed && target.send(frame)
        }
    }

    private val registry = TerminalRegistry()

    /** 当前（界面）终端会话；没连时为 null。 */
    fun currentSession(): TerminalSession? = registry.uiSession()

    /** 按名取会话（MCP 侧用）。 */
    fun session(name: String): TerminalSession? = registry.get(name)

    /** 当前所有已连会话的名字。 */
    fun sessionNames(): List<String> = registry.names()

    /**
     * 打开终端的 WebSocket，开始收发命令。
     *
     * Jupyter 终端的 WebSocket 协议是 **JSON 数组**（terminado，不是内核那套 dict 消息）：
     * 发 `["stdin", "命令\r"]`，收 `["stdout", "输出"]`，改尺寸用 `["set_size", rows, cols]`。
     * 以前发的是 `{"type":"stdin",...}`，服务器当成畸形消息直接把连接掉了——
     * 现象就是“刚连上、一输命令就断开”。
     *
     * 同名终端会被替换（重连就是这条路）；**不同名的会话保留**。
     *
     * @param asUiCurrent 是否把它设为"界面正在用的终端"。**界面传 true，MCP 传 false**——
     *   v0.2.98 修 P0-2：以前无条件抢占，于是 AI 一开终端就把界面那条顶掉，
     *   用户在控制台敲的命令全发到 AI 的终端去了。
     */
    fun openTerminal(
        account: AiStudioAccount,
        endpoint: KernelEndpoint,
        name: String,
        asUiCurrent: Boolean = false,
        onOutput: (String) -> Unit,
        onOpen: () -> Unit,
        onClosed: (String) -> Unit,
    ) {
        // 同名会话先关：重连（或换账号重连）时旧 socket 不能留着跟新的抢输出。
        closeSession(name)
        // v0.2.51：本次连接的代次 id。close 是异步的，换连接/切账号时旧 socket
        // 的 onOpen / onMessage / onFailure / onClosed 都可能晚到。只看
        // `session.socket !== webSocket` 已经挡住大部分，但 onMessage 的输出、以及
        // 新连接建立后旧连接的输出，仍可能混进来。统一用连接 id 做身份判定。
        val connectionId = UUID.randomUUID().toString()
        val url = withToken(endpoint, wsBase(endpoint) + "terminals/websocket/" + encode(name))
        val builder = Request.Builder().url(url)
        commonHeaders(account, endpoint).forEach { (k, v) -> builder.header(k, v) }
        val session = TerminalSession(name = name, connectionId = connectionId, socket = null)
        val socket = client.newWebSocket(
            builder.build(),
            object : WebSocketListener() {
                /** 只有「当前仍是这个会话」的回调才算数（旧 socket 的迟到回调要丢弃）。 */
                private fun stale(webSocket: WebSocket): Boolean {
                    val registered = registry.get(name)
                    return registered !== session ||
                        session.connectionId != connectionId ||
                        session.socket !== webSocket
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // v0.2.49：旧连接的迟到 onOpen 若不拦，会把 terminalManualClose 改回
                    // false（用户刚点断开却被自动重连）、并把 consoleConnected 置 true
                    // （顶栏报"已连接"实际没有 socket）。
                    if (stale(webSocket)) return
                    onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // v0.2.51：旧连接的迟到输出不能写进当前终端日志（切账号后旧机器仍在
                    // 吐输出，混进来会让人以为命令发错了机器）。
                    if (stale(webSocket)) return
                    val raw = AiStudioProtocol.parseTerminalOutput(text) ?: return
                    // 只剔 OSC/CSI 等控制序列，**保留** ANSI 颜色码——由界面渲染成颜色，
                    // 不然 ls 的着色、彩色提示符全没了，一屏白字看起来又乱又平。
                    // 先把上一帧残留的半截转义拼上，避免序列被帧边界切开后残留乱码。
                    val combined = session.pendingEscape.toString() + raw
                    session.pendingEscape.setLength(0)
                    val (complete, pending) = AiStudioProtocol.splitTrailingIncompleteEscape(combined)
                    if (pending.isNotEmpty()) session.pendingEscape.append(pending)
                    val clean = AiStudioProtocol.sanitizeTerminalOutput(complete)
                    if (clean.isNotEmpty()) onOutput(clean)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (stale(webSocket)) return
                    detach(session)
                    onClosed(t.message ?: "连接中断")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (stale(webSocket)) return
                    detach(session)
                    onClosed("终端已关闭（$code）")
                }
            },
        )
        session.attach(socket)
        registry.put(session)
        // 只有显式要求时才抢"界面当前终端"。MCP 传 false，不会顶掉用户那条。
        if (asUiCurrent) registry.setUi(session)
    }

    /** 从表里摘掉某个会话（仅当表里装的还是它）。 */
    private fun detach(session: TerminalSession) {
        registry.removeIfSame(session)
    }

    /**
     * 关掉某个终端的 socket（不删服务端 PTY）。
     *
     * 先作废会话再关 socket：否则旧 socket 的迟到回调仍可能被当成当前连接。
     */
    fun closeSession(name: String) {
        val existing = registry.remove(name) ?: return
        existing.socket?.close(1000, "client close")
    }

    /** 向终端发一条命令（自动补回车）。协议帧：`["stdin", "...\r"]`。 */
    fun sendInput(command: String, session: TerminalSession? = null): Boolean {
        val target = session ?: currentSession() ?: return false
        return target.send(AiStudioProtocol.terminalStdinFrame(command))
    }

    /**
     * 向终端发送原始按键（不补回车）。
     *
     * 用于 Ctrl+C（`\u0003`）这类控制字符——它们是裸字节，补上 `\r` 反而会被当成
     * 回车提交。协议帧同样是 `["stdin", "..."]`，只是内容不带 `\r`。
     */
    fun sendRawInput(raw: String, session: TerminalSession? = null): Boolean {
        val target = session ?: currentSession() ?: return false
        return target.send(AiStudioProtocol.terminalRawStdinFrame(raw))
    }

    /** 告知终端窗口尺寸，避免输出错行。协议帧：`["set_size", rows, cols]`（注意顺序）。 */
    fun resize(cols: Int, rows: Int, session: TerminalSession? = null) {
        val target = session ?: currentSession() ?: return
        target.send(AiStudioProtocol.terminalResizeFrame(rows, cols))
    }

    /** 关掉界面那个终端（保留其它 MCP 会话）。 */
    fun closeTerminal() {
        registry.uiSession()?.let { closeSession(it.name) }
    }

    /** 关掉全部终端会话（切账号 / 断开项目时用）。 */
    fun closeAllTerminals() {
        registry.names().forEach { closeSession(it) }
    }

    /**
     * 导出 CookieJar 里当前的 Cookie 串（`name=value; name2=value2`）。
     *
     * 连终端的过程中，AI Studio 网关（nginx）会下发**项目级** Cookie——`ide-proxy`
     * 和 `user-{uid}-{pid}`——它们才是 `api_serving` 反代（ComfyUI 地址）真正校验的东西。
     * 只带账号 Cookie（BDUSS 等）会被 302 回登录页；这也是为什么以前“手动连接
     * ComfyUI 要手粘 Cookie”。这里把网关下发的这份导出给 ComfyUI 连接复用。
     */
    fun exportCookies(): String = synchronized(cookieLock) {
        cookieStore.values
            .asSequence()
            .flatten()
            .distinctBy { it.name }
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    /**
     * 当前已捕获的 Cookie **名字**（不含值）。
     *
     * 仅用于诊断：判断网关是否下发了项目级 Cookie（`ide-proxy`、`user-*`）。
     * 值等同账号密码，绝不写进日志。
     */
    fun cookieNames(): List<String> = synchronized(cookieLock) {
        cookieStore.values
            .asSequence()
            .flatten()
            .map { it.name }
            .distinct()
            .sorted()
            .toList()
    }

    /** 是否已拿到 api_serving 反代鉴权必需的项目级 Cookie。 */
    fun hasProjectCookies(): Boolean {
        val names = cookieNames()
        return names.contains("ide-proxy") || names.any { it.startsWith("user-") }
    }

    /**
     * 丢掉项目级 Cookie（`ide-proxy` / `user-*`），账号级 Cookie 保留（v0.2.46）。
     *
     * 切换 AI Studio 账号时必须调它。项目级 Cookie 是绑定在**具体实例**上的
     * （`user-{uid}-{pid}` 里就带着 uid 和 pid），换账号后它们属于上一个账号：
     * 留着会让新账号的请求带着旧身份，轻则 403 / 登录墙，重则打到旧账号的实例上。
     * 同一账号"停掉 GPU 再启动"也一样——实例重建后旧 `ide-proxy` 已经失效，
     * 但名字还在，`hasProjectCookies()` 仍返回 true，连接前就不会去换新 Cookie。
     */
    fun clearProjectCookies() {
        synchronized(cookieLock) {
            cookieStore.keys.toList().forEach { host ->
                val list = cookieStore[host] ?: return@forEach
                list.removeAll { cookie ->
                    cookie.name == "ide-proxy" || cookie.name.startsWith("user-")
                }
            }
        }
        AppLogger.info("已清除项目级 Cookie，剩余 Cookie=[${cookieNames().joinToString()}]")
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
        // v0.3.9：这里是**内部不变量**（正常不可达）。
        //
        // 拿不到地址的情况现在在 fetchEndpoint 里就拦下了，并招出
        // [AiStudioEnvironmentGoneException] 带清楚原因与下一步；
        // 所以走到这里还为空，说明是代码路径出错（如有人用手工构造的 endpoint）。
        if (raw.isBlank()) throw AiStudioException("终端通道内部错误：endpoint 没有任何地址")
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

    private fun currentXsrf(): String? = synchronized(cookieLock) {
        cookieStore.values
            .asSequence()
            .flatten()
            .firstOrNull { it.name == "_xsrf" }
            ?.value
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
            // v0.3.10（真机日志）：**先判登录墙**，再判 HTTP 状态码。
            //
            // 真机现象：Cookie 失效后网关 302 跳登录页，返回一个 HTML 页面。
            //   GET  api/terminals → 200 text/html（经 302 → /aistudio/projectdetail/…）
            //   POST api/terminals → 405 Method Not Allowed
            // 旧代码把前者当正常（JSONArray 解不了 → 静默 emptyList），
            // 把后者报成"新建终端失败：HTTP 405"（看着像接口坏了）。
            // 结果 App 只会重连 8 次刷屏，**永远不告诉用户登录态已失效**。
            ensureNotLoginWall(resp, body, action)
            if (!resp.isSuccessful) throw AiStudioException("${action}失败：HTTP ${resp.code}")
            val redirected = resp.priorResponse?.let { "（经重定向 ${it.code} → ${resp.request.url}）" }.orEmpty()
            AppLogger.info("${action}：HTTP ${resp.code} ${resp.header("Content-Type").orEmpty()}$redirected")
            body
        }
    }

    /**
     * 登录墙识别（v0.3.10）。任一信号命中就抛 [AiStudioLoginRequiredException]。
     *
     * ## 为什么必须单独判（真机日志）
     *
     * ```
     * 读取终端列表：HTTP 200 text/html（经重定向 302 → https://…/aistudio/projectdetail/10707054）
     * 终端列表响应：<!doctype html><title>飞桨AI Studio星河社区…</title>
     * 新建终端失败：HTTP 405（Method Not Allowed）
     * → 终端重连失败（第 1~8 次）… 直到「连续 8 次重连失败，停止重试并清理连接」
     * ```
     *
     * 这不是"终端接口坏了"，而是**登录态失效了**：网关把所有未鉴权请求 302 到
     * 登录页，返回 HTML。旧代码把 HTML 当 JSON 解，`JSONArray()` 失败就静默返回
     * 空列表——用户看到"项目里没有终端"，完全不知道是登录过期；POST 更直接回 405，
     * 看着像方法不允许。
     *
     * 按「登录墙」处理就能给出真正可行动的话：回 App 重新登录，而不是无限重连。
     */
    private fun ensureNotLoginWall(resp: Response, body: String, action: String) {
        val contentType = resp.header("Content-Type").orEmpty()
        val finalUrl = resp.request.url.toString()
        // 判定本体在协议层（纯函数，可单测）——这里只做取信号 + 报错。
        val loginWall = AiStudioProtocol.isLoginWall(
            contentType = contentType,
            finalUrl = finalUrl,
            hadRedirect = resp.priorResponse != null,
            body = body,
        )
        if (!loginWall) return
        AppLogger.warn(
            "$action 撞上登录墙（Content-Type=$contentType，重定向=${resp.priorResponse != null}，" +
                "最终地址=$finalUrl）—— 登录态已失效",
        )
        throw AiStudioLoginRequiredException(
            "AI Studio 登录态已失效：$action 被重定向到登录页。" +
                "请回 App「账号」页重新登录后重试；这不是终端接口故障，反复重连也不会好。",
        )
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
            // v0.3.10（真机日志）：**先判登录墙**，再判 HTTP 状态码。
            //
            // 真机现象：Cookie 失效后网关 302 跳登录页，返回一个 HTML 页面。
            //   GET  api/terminals → 200 text/html（经 302 → /aistudio/projectdetail/…）
            //   POST api/terminals → 405 Method Not Allowed
            // 旧代码把前者当正常（JSONArray 解不了 → 静默 emptyList），
            // 把后者报成"新建终端失败：HTTP 405"（看着像接口坏了）。
            // 结果 App 只会重连 8 次刷屏，**永远不告诉用户登录态已失效**。
            ensureNotLoginWall(resp, body, action)
            if (!resp.isSuccessful) throw AiStudioException("${action}失败：HTTP ${resp.code}")
            val redirected = resp.priorResponse?.let { "（经重定向 ${it.code} → ${resp.request.url}）" }.orEmpty()
            AppLogger.info("${action}：HTTP ${resp.code} ${resp.header("Content-Type").orEmpty()}$redirected")
            body
        }
    }
}
