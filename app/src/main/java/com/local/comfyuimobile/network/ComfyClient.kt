package com.local.comfyuimobile.network

import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.model.DeviceStats
import com.local.comfyuimobile.model.JobState
import com.local.comfyuimobile.model.JobSummary
import com.local.comfyuimobile.model.ServerProfile
import com.local.comfyuimobile.model.SystemStats
import com.local.comfyuimobile.model.WorkflowEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import okio.source

data class QueueResponse(val promptId: String, val number: Int, val nodeErrors: JSONObject?)
data class UploadResponse(val name: String, val subfolder: String, val type: String)
class PromptSubmissionException(
    message: String,
    val nodeProblems: Map<String, List<String>>,
) : IllegalStateException(message)

class ComfyClient {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    @Volatile private var authCookie: String = ""
    private val client = OkHttpClient.Builder()
        // 云端 / 公网服务器握手明显慢于局域网，4 秒会误判为不可达，放宽到 15 秒。
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        // 反向代理登录态：用户手动配置的 Cookie（如 AI Studio api_serving 的
        // 鉴权 Cookie）附加到每个请求；未配置则不加请求头。
        .addInterceptor { chain ->
            val original = chain.request()
            if (authCookie.isBlank()) {
                chain.proceed(original)
            } else {
                chain.proceed(original.newBuilder().header("Cookie", authCookie).build())
            }
        }
        .build()

    @Volatile private var baseUrl: String = ""
    @Volatile private var socket: WebSocket? = null

    /**
     * v0.1.68：记录这台服务器实际支持哪些 ComfyUI 接口。
     * 反向代理（百度 AI Studio 等）不开放 /userdata，以前每 11 秒刷一次、
     * 17 分钟刷了 56 次，日志被同一页 HTML 彻底淹没。
     */
    private val capabilities = ServerCapabilities()

    /**
     * 路径是否需要双重百分号编码。
     *
     * 百度 AI Studio 的反向代理会把 URL 路径解掉一层编码再转发给后端，中文工作流名
     * （如"任务快照-xxx.json"）于是变成裸的非 ASCII 字节，被 Python 侧的 HTTP 服务器
     * 直接以 `Invalid char in url path`（HTTP 400）拒掉——日志里 16:08:50 的
     * "工作流另存失败"就是它。
     *
     * 把 `%` 再编码成 `%25`，代理解掉一层之后到达 ComfyUI 的正好是单次编码，
     * aiohttp 解出来还是原来的中文。只对实测出问题的服务器启用。
     */
    @Volatile private var doubleEncodedUserdataPath = false

    fun setServer(url: String) {
        baseUrl = url.trimEnd('/')
        doubleEncodedUserdataPath = false
    }

    /** 供 UI 查询"这台服务器有哪些功能不可用"，用于给出明确提示。 */
    fun capabilities(): ServerCapabilities = capabilities

    /** 当前服务器不可用的功能摘要（空串表示全部可用）。 */
    fun unsupportedFeatures(): String = capabilities.unavailableSummary(baseUrl)

    fun serverUrl(): String = baseUrl

    /** 设置反向代理认证 Cookie（空串表示不启用）。 */
    fun setAuthCookie(cookie: String) {
        authCookie = cookie.trim()
    }

    fun authCookie(): String = authCookie

    /**
     * v0.1.68：反向代理（百度 AI Studio 等）在实例冷启动 / 会话尚未建立时，第一次
     * 请求会返回平台自己的错误页而不是 JSON——有时还伪装成 HTTP 200。日志里用户
     * 连点五次连接、前四次全栽在这上面。这里对"拿到的是网页而不是 JSON"这种情况
     * 自动重试两次，把冷启动的抖动吃掉，而不是让用户反复点。
     */
    private suspend fun probeOnceWithRetry(normalized: String): JSONObject = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        repeat(PROBE_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(PROBE_RETRY_DELAYS_MS[attempt - 1])
            val outcome = runCatching {
                executeJson(Request.Builder().url("$normalized/system_stats").get().build())
            }
            if (outcome.isSuccess) return@withContext outcome.getOrThrow()
            val error = outcome.exceptionOrNull() ?: return@repeat
            // runCatching 连取消信号一起吞了，这里必须放行，否则退出页面时
            // 这个协程会赖着不走，把下一次连接的请求也一起搅乱。
            if (error is CancellationException) throw error
            lastError = error
            // 只在"服务器返回了网页"或 5xx 时重试：这是冷启动抖动的典型特征。
            // ComfyUI 自己返回的 4xx JSON 错误、DNS 失败、连接被拒都不重试，
            // 免得把本来秒回的错误拖成 5 秒超时。
            // v0.1.69：登录页同样不该重试——Cookie 不会因为多试两次就自己出现。
            // 日志里 16:04:38 起连着 8 次"需要登录或登录已失效"，每次都要干等三轮
            // 退避，用户看到的只是"转半天圈然后告诉我没登录"。
            val retriable = error is PlatformResponseException && error.retriable
            if (!retriable) throw error
            AppLogger.warn("服务器第 ${attempt + 1}/$PROBE_ATTEMPTS 次探测返回了网页或 5xx，准备重试", error)
        }
        throw lastError ?: IllegalStateException("服务器探测失败")
    }

    suspend fun probe(url: String = baseUrl): Pair<SystemStats, ServerProfile> = withContext(Dispatchers.IO) {
        val normalized = LanAddress.normalize(url)
        val root = probeOnceWithRetry(normalized)
        val stats = parseSystemStats(root)
        val host = LanAddress.displayHost(normalized)
        stats to ServerProfile(
            id = UUID.nameUUIDFromBytes(normalized.toByteArray()).toString(),
            name = host,
            baseUrl = normalized,
            lastSeen = System.currentTimeMillis(),
            comfyVersion = stats.comfyVersion,
        )
    }

    suspend fun systemStats(): SystemStats = withContext(Dispatchers.IO) {
        parseSystemStats(getJson("/system_stats"))
    }

    suspend fun features(): JSONObject = withContext(Dispatchers.IO) { getJson("/features") }

    suspend fun objectInfo(): JSONObject = withContext(Dispatchers.IO) { getJson("/object_info") }

    /**
     * v0.1.68：在能力门控下执行 /userdata 请求。
     *
     * 判定为"平台不支持"后进入指数退避（30s → 60s → … → 10 分钟封顶），
     * 而不是继续定时刷。日志里 17 分钟刷 56 次同一页 HTML 就是这么来的。
     */
    private fun <T> userdataCall(block: () -> T): T {
        val capability = ServerCapabilities.Capability.USERDATA
        val key = baseUrl
        if (!capabilities.shouldRetry(key, capability, System.currentTimeMillis())) {
            throw PlatformResponseException(
                capabilities.reason(key, capability) ?: "该服务器不支持云端工作流，已暂停重试",
                404,
                true,
            )
        }
        // 最多跑两遍：第二遍是在"路径被代理解掉一层编码"时，改用双重编码再试。
        repeat(2) { attempt ->
            try {
                return block().also { capabilities.markSuccess(key, capability) }
            } catch (error: Exception) {
                // runCatching 之后这里也要放行取消信号，否则退出页面后协程会赖着不走。
                if (error is CancellationException) throw error
                val shouldDoubleEncode = attempt == 0 &&
                    !doubleEncodedUserdataPath &&
                    error is PlatformResponseException &&
                    needsDoubleEncodedPath(error)
                if (shouldDoubleEncode) {
                    doubleEncodedUserdataPath = true
                    AppLogger.warn("服务器拒绝路径里的中文（HTTP 400），改用双重编码重试一次", error)
                    return@repeat
                }
                when (error) {
                    is PlatformResponseException -> capabilities.markFailure(
                        key, capability, System.currentTimeMillis(),
                        error.message.orEmpty(), error.unsupported,
                    )
                    // 超时、连接中断属于暂时故障，不该把接口永久标记成"不支持"
                    else -> capabilities.markFailure(
                        key, capability, System.currentTimeMillis(),
                        error.message.orEmpty(), false,
                    )
                }
                throw error
            }
        }
        throw IllegalStateException("云端工作流请求未返回结果")
    }

    /** 服务器/代理把 URL 路径里的非 ASCII 字节直接拒了的典型报错。 */
    private fun needsDoubleEncodedPath(error: PlatformResponseException): Boolean =
        error.httpCode == 400 &&
            error.message.orEmpty().contains("Invalid char in url path", ignoreCase = true)

    suspend fun listWorkflows(): List<WorkflowEntry> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/v2/userdata?path=${encode("workflows")}" 
        val array = userdataCall {
            executeArray(Request.Builder().url(url).get().build())
        }
        buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val type = item.optString("type")
                val path = item.optString("path")
                if (type == "directory" || path.endsWith(".json", ignoreCase = true)) {
                    add(
                        WorkflowEntry(
                            name = item.optString("name"),
                            path = path,
                            isDirectory = type == "directory",
                            size = item.optLong("size"),
                            modified = item.optDouble("modified"),
                        ),
                    )
                }
            }
        }.sortedWith(compareBy<WorkflowEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.path })
    }

    suspend fun readWorkflow(path: String): String = withContext(Dispatchers.IO) {
        userdataCall { executeText(Request.Builder().url(userdataUrl(path)).get().build()) }
    }

    suspend fun writeWorkflow(path: String, json: String, overwrite: Boolean): WorkflowEntry = withContext(Dispatchers.IO) {
        // 同 moveWorkflow：URL 放 block 里，重试时才吃得到双重编码开关。
        val response = userdataCall {
            val url = userdataUrl(path) + "?overwrite=$overwrite&full_info=true"
            executeJson(Request.Builder().url(url).post(json.toRequestBody(jsonMedia)).build())
        }
        WorkflowEntry(
            name = path.substringAfterLast('/'),
            path = response.optString("path", path),
            isDirectory = false,
            size = response.optLong("size", json.toByteArray().size.toLong()),
            modified = response.optDouble("modified", System.currentTimeMillis() / 1000.0),
        )
    }

    suspend fun moveWorkflow(source: String, destination: String): WorkflowEntry = withContext(Dispatchers.IO) {
        // URL 必须在 block 里构造：双重编码开关是第一次失败后才打开的，
        // 提前算好 URL 的话，重试时用的还是旧编码。
        val response = userdataCall {
            val url = userdataUrl(source) + "/move/" +
                UserdataPath.encode(destination, doubleEncode = doubleEncodedUserdataPath) +
                "?overwrite=false&full_info=true"
            executeJson(Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build())
        }
        WorkflowEntry(
            name = destination.substringAfterLast('/'),
            path = response.optString("path", destination),
            isDirectory = false,
            size = response.optLong("size"),
            modified = response.optDouble("modified"),
        )
    }

    suspend fun deleteWorkflow(path: String) = withContext(Dispatchers.IO) {
        userdataCall {
            executeText(
                Request.Builder().url(userdataUrl(path)).delete().build(),
                allowedCodes = setOf(200, 204),
            )
        }
    }

    suspend fun queue(): List<JobSummary> = withContext(Dispatchers.IO) {
        val root = getJson("/queue")
        parseQueueArray(root.optJSONArray("queue_running"), JobState.RUNNING) +
            parseQueueArray(root.optJSONArray("queue_pending"), JobState.PENDING)
    }

    suspend fun history(maxItems: Int? = null): JSONObject = withContext(Dispatchers.IO) {
        getJson(if (maxItems == null) "/history" else "/history?max_items=$maxItems")
    }

    suspend fun history(promptId: String): JSONObject = withContext(Dispatchers.IO) {
        getJson("/history/${encode(promptId)}")
    }

    suspend fun historyJobs(maxItems: Int? = null): List<JobSummary> = withContext(Dispatchers.IO) {
        val history = history(maxItems)
        buildList {
            history.keys().forEach { id ->
                val item = history.optJSONObject(id) ?: return@forEach
                val status = item.optJSONObject("status")
                val statusString = status?.optString("status_str").orEmpty()
                val completed = status?.optBoolean("completed") == true
                val state = when {
                    statusString.equals("error", true) -> JobState.ERROR
                    completed -> JobState.SUCCESS
                    else -> JobState.UNKNOWN
                }
                val extraData = item.optJSONArray("prompt")?.optJSONObject(3)
                add(
                    JobSummary(
                        id = id,
                        state = state,
                        workflowName = workflowName(extraData),
                        workflowPath = workflowPath(extraData),
                        workflowJson = workflowJson(extraData),
                        message = statusString,
                        durationMillis = executionDuration(status),
                    ),
                )
            }
        }
    }

    suspend fun queuePrompt(
        promptJson: String,
        workflowJson: String,
        clientId: String,
        workflowPath: String,
        workflowName: String,
    ): QueueResponse = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("client_id", clientId)
            .put("prompt", JSONObject(promptJson))
            .put(
                "extra_data",
                JSONObject()
                    .put("extra_pnginfo", JSONObject().put("workflow", JSONObject(workflowJson)))
                    .put(
                        "comfy_mobile",
                        JSONObject()
                            .put("workflow_path", workflowPath)
                            .put("workflow_name", workflowName),
                    ),
            )
        val request = Request.Builder().url("$baseUrl/prompt").post(body.toString().toRequestBody(jsonMedia)).build()
        client.newCall(request).execute().use { httpResponse ->
            val responseBody = httpResponse.body?.string().orEmpty()
            // v0.1.78：失败响应允许正文不是 JSON（代理经常回整页 HTML），顶个空对象
            // 只为去取 error 字段；成功响应绝不能这么干。以前 200 + 非 JSON 被静默替换
            // 成空对象，紧接着 getString("prompt_id") 抛 "No value for prompt_id"，
            // 用户看到的提示和真实原因没有半点关系（真实原因通常是代理返回了网页）。
            val response = runCatching { JSONObject(responseBody) }.getOrElse {
                if (httpResponse.isSuccessful) throw PlatformResponseException(
                    "生成请求已发出，但服务器返回的不是 JSON：${PlatformResponseGuard.describe(httpResponse.code, responseBody)}",
                    httpResponse.code,
                    unsupported = PlatformResponseGuard.isHtml(responseBody),
                    html = PlatformResponseGuard.isHtml(responseBody),
                )
                JSONObject()
            }
            if (!httpResponse.isSuccessful) {
                val error = response.optJSONObject("error")
                val message = when (error?.optString("type")) {
                    "prompt_outputs_failed_validation" -> "部分部件参数校验失败，请查看标红的部件"
                    "prompt_no_outputs" -> "当前工作流没有可执行的输出节点"
                    "invalid_prompt" -> "生成参数格式无效"
                    else -> error?.optString("message").takeUnless { it.isNullOrBlank() }
                        ?.let { "服务器校验失败：$it" }
                        ?: "服务器拒绝了生成参数（HTTP ${httpResponse.code}）"
                }
                throw PromptSubmissionException(message, parseNodeProblems(response.optJSONObject("node_errors")))
            }
            // v0.1.78：同样别让它退化成 "No value for prompt_id"——说清楚服务器回了什么。
            val promptId = response.optString("prompt_id")
            if (promptId.isBlank()) {
                throw IllegalStateException(
                    "服务器没有返回任务编号，无法跟踪进度（${PlatformResponseGuard.describe(httpResponse.code, responseBody)}）",
                )
            }
            QueueResponse(
                promptId = promptId,
                number = response.optInt("number"),
                nodeErrors = response.optJSONObject("node_errors"),
            )
        }
    }

    suspend fun upload(
        filename: String,
        mimeType: String?,
        contentLength: Long,
        inputStream: () -> InputStream,
        subfolder: String,
    ): UploadResponse = withContext(Dispatchers.IO) {
        val fileBody = object : RequestBody() {
            override fun contentType() = mimeType?.toMediaTypeOrNull()
            override fun contentLength(): Long = contentLength
            override fun writeTo(sink: okio.BufferedSink) {
                inputStream().use { source -> sink.writeAll(source.source()) }
            }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", filename, fileBody)
            .addFormDataPart("type", "input")
            .addFormDataPart("subfolder", subfolder)
            .addFormDataPart("overwrite", "false")
            .build()
        val response = executeJson(Request.Builder().url("$baseUrl/upload/image").post(body).build())
        UploadResponse(response.getString("name"), response.optString("subfolder"), response.optString("type", "input"))
    }

    /**
     * 取消任务。
     *
     * v0.1.71 改动：原生 ComfyUI **没有** `/api/jobs/{id}` 这个接口（那是 Manager 一类插件
     * 才提供的），所以它必然 404，然后刀就落到了 `/interrupt` 上。问题是 /interrupt 的语义
     * 是"中断**当前正在执行**的那一个任务"——如果目标任务其实已经跑完、或者被别的客户端
     * 插队顶掉了，这一刀砍的就是别人正在跑的任务。
     *
     * 现在的做法：
     * 1. 先照常发 `/queue` delete。对排队中的任务这就是取消本身；对运行中的任务它不起作用，
     *    但也不会误伤（ComfyUI 的 delete 只作用于排队队列）。
     * 2. 只有确认目标还在 `queue_running` 里，才发 /interrupt。确认不了就干脆不动手。
     *
     * v0.1.78：上面第 2 步以前只在 RUNNING 分支做，PENDING 分支删完队列就 return。
     * 可 `job.state` 来自任务列表轮询（默认 5 秒一次），是**快照**——任务完全可能在
     * 快照之后就已出队开跑。这时候 delete 打空，/interrupt 又被跳过，用户点了取消，
     * 任务照样一路跑完，界面还显示已取消。比报错更糟的是它静默失败。
     * 现在两条路径删完都按服务端实时状态复查一次，复查只认目标 promptId，不会误伤。
     */
    suspend fun cancel(job: JobSummary) = withContext(Dispatchers.IO) {
        val delete = JSONObject().put("delete", JSONArray().put(job.id))
        val deleteRequest = Request.Builder().url("$baseUrl/queue").post(delete.toString().toRequestBody(jsonMedia)).build()
        if (job.state == JobState.PENDING) {
            // 排队中的任务必须摘掉才算取消，删失败就得如实报错，不能装成功。
            executeText(deleteRequest)
        } else {
            runCatching { executeText(deleteRequest) }
                .onFailure { error -> AppLogger.warn("取消任务时清理队列失败：${job.id}", error) }
        }
        if (!isJobRunning(job.id)) {
            AppLogger.info("任务 ${job.id} 已不在执行队列中，跳过中断以免误伤其他任务")
            return@withContext
        }
        executeText(Request.Builder().url("$baseUrl/interrupt").post(ByteArray(0).toRequestBody()).build())
    }

    /**
     * 目标任务是否真的还站在执行队列里。
     * 读不到队列时保守返回 false——宁可这次取消不生效，也不能误中断别人的任务。
     */
    private fun isJobRunning(promptId: String): Boolean = runCatching {
        val root = executeJson(Request.Builder().url("$baseUrl/queue").get().build())
        val running = root.optJSONArray("queue_running") ?: return@runCatching false
        for (index in 0 until running.length()) {
            // /queue 的每一项形如 [jobIndex, promptId, prompt, extraData, outputsToExecute]
            val item = running.optJSONArray(index) ?: continue
            if (item.length() >= 2 && item.optString(1) == promptId) return@runCatching true
        }
        false
    }.getOrDefault(false)

    suspend fun clearPending() = withContext(Dispatchers.IO) {
        val body = JSONObject().put("clear", true)
        executeText(Request.Builder().url("$baseUrl/queue").post(body.toString().toRequestBody(jsonMedia)).build())
    }

    fun openWebSocket(clientId: String, onMessage: (JSONObject) -> Unit, onFailure: (Throwable) -> Unit, onOpen: () -> Unit) {
        closeWebSocket()
        // 判断忽略大小写、截取也必须忽略大小写，否则 HTTPS:// 会拼出 wssHTTPS:// 这种非法地址。
        val wsBase = when {
            baseUrl.startsWith("https", ignoreCase = true) -> "wss" + baseUrl.substring("https".length)
            baseUrl.startsWith("http", ignoreCase = true) -> "ws" + baseUrl.substring("http".length)
            else -> baseUrl
        }
        val request = Request.Builder().url("$wsBase/ws?clientId=${encode(clientId)}").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { onMessage(JSONObject(text)) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onFailure(t)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // v0.1.81：自己关的连接不能当成掉线。closeWebSocket() 是异步的——
                // 它发出关闭帧后由 OkHttp 在读取线程回调 onClosed——而调用它的两处
                // （openWebSocket 开头换连接、MainViewModel.disconnect 断开）都在
                // "我正要换/关" 的语境里。以前不区分，主动关闭也会回调 onFailure，
                // 于是：① 用户点断开，界面闪一下"连接中断，正在重连"；
                // ② 重连成功后 openSocket 会先关旧连接，那个回调又启动一轮重连，
                // 和刚建立的连接抢状态。用我们自己传的 1000 + "switch server"
                // 认出主动关闭，直接吞掉。
                if (code == NORMAL_CLOSURE && reason == CLOSE_REASON) return
                onFailure(IllegalStateException("WebSocket 已关闭：$code $reason"))
            }
        })
    }

    fun closeWebSocket() {
        socket?.close(NORMAL_CLOSURE, CLOSE_REASON)
        socket = null
    }

    fun mediaUrl(filename: String, subfolder: String, type: String): String =
        "$baseUrl/view?filename=${encode(filename)}&subfolder=${encode(subfolder)}&type=${encode(type)}"

    /**
     * 下载图片/视频到指定输出流。
     *
     * v0.1.79 补了两道守卫。这里走的是裸的 `client.newCall`，**不经过**
     * [executeText]，也就没有 [PlatformResponseGuard] 把关，而反向代理托管的
     * ComfyUI（百度 AI Studio 一类）恰恰最爱在这条路径上出问题：
     *  1. 会话过期时 `/view` 返回 **HTTP 200 + 登录页 HTML**（不是 401）。以前原样
     *     写进文件，用户分享出去、存进相册的是一个打不开的网页，还以为图片坏了。
     *  2. 401/403 的错误提示只有一句"下载失败：HTTP 403"，看不出是登录失效。
     *
     * 现在先看响应开头是不是网页：是网页就按守门的口径报人话（"需要登录或登录
     * 已失效"），写不出半个 HTML 文件。另外正文为空也直接报错，不再落一个 0 字节文件。
     *
     * 说明：请求本身**是带鉴权 Cookie 的**——Cookie 由 OkHttpClient 的拦截器统一
     * 附加（见 [authCookie]），这条路径和 /prompt、/history 走的是同一个拦截器。
     */
    suspend fun downloadTo(url: String, output: OutputStream) = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            // peekBody 只预览前若干字节：okio 的 PeekSource 共享底层 Buffer 但读游标
            // 独立，是"复制"而不是"消耗"，后续 byteStream() 拿到的仍是完整数据。
            // （v0.1.80 实测核对：一张 42399 字节的 PNG 走完整流程落盘，MD5 与源文件
            //  一致、PIL 可正常解码；分块/大图同样适用。）
            val head = runCatching { response.peekBody(SNIFF_BYTES).string() }.getOrDefault("")
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "下载失败：" + PlatformResponseGuard.describe(response.code, head),
                )
            }
            val body = response.body ?: throw IllegalStateException("下载内容为空")
            if (PlatformResponseGuard.isHtml(head)) {
                throw IllegalStateException("下载到的不是图片：" + PlatformResponseGuard.describe(response.code, head))
            }
            val written = output.use { target -> body.byteStream().use { source -> source.copyTo(target) } }
            if (written == 0L) throw IllegalStateException("下载内容为空（服务器返回了 0 字节）")
            // v0.1.80 兜底：服务器声明了长度却没写够，说明正文被截断（代理掐断、
            // 或者将来某个 OkHttp 版本的 peek 语义变了）。宁可报错让调用方删掉这个文件，
            // 也不能把半张图存进相册——那种坏文件用户只能靠"打不开"才发现。
            // 分块传输（Content-Length 缺失时返回 -1）无从比对，跳过。
            val declared = body.contentLength()
            if (declared > 0 && written != declared) {
                throw IllegalStateException("下载内容不完整：只收到 $written / $declared 字节，已放弃保存")
            }
        }
    }

    private fun getJson(path: String): JSONObject = executeJson(Request.Builder().url(baseUrl + path).get().build())

    /**
     * v0.1.78：HTTP 200 只说明守门没拦下（非网页、状态码合法），**不说明正文是我们
     * 要的那种 JSON**。反向代理特别爱用 200 + 另一种形状的响应糊弄人：列表接口返回
     * JSON 对象、详情接口返回数组、甚至一行纯文本。以前直接丢给 JSONObject/JSONArray
     * 构造方法，抛出来的是 "Value [...] of type org.json.JSONArray cannot be converted
     * to JSONObject" 这种只有程序员才看得懂的话，还被 userdataCall 当成普通失败记进
     * 能力表。先按首字符判定，给出人话，并标成平台不支持（成功一次即自动恢复）。
     */
    private fun executeJson(request: Request): JSONObject {
        val body = executeText(request)
        requireJsonShape(body, '{', "服务器返回的不是 JSON 对象")
        return JSONObject(body)
    }

    private fun executeArray(request: Request): JSONArray {
        val body = executeText(request)
        requireJsonShape(body, '[', "服务器返回的不是 JSON 数组")
        return JSONArray(body)
    }

    /**
     * v0.1.81：形状不对**不等于**平台不支持，这里区分三种情况，别一刀切。
     *
     * 以前一律 `unsupported = true`，一个 writeWorkflow 拿到数组就会把
     * `Capability.USERDATA` 整个标记成"不支持"并退避 30 秒——连坐了 listWorkflows、
     * readWorkflow、deleteWorkflow，用户看到的是"该服务器不支持云端工作流"，
     * 而真实原因可能只是一次响应抖动。
     *
     * 现在按正文内容判定，与 [PlatformResponseGuard.guard] 对齐：
     *  - **网页**（HTML）：平台真不支持（或登录墙），退避；
     *  - **空内容**：百度 AI Studio 网关的固定表现（实测 200 + 空 body），
     *    同样是"这条路走不通"，退避，免得每 11 秒刷一次；
     *  - **有内容但形状不对**：可能是服务器一次性的错误响应（比如返回了
     *    `{"error": ...}` 而不是列表），按普通失败记一笔，下次照常请求。
     */
    private fun requireJsonShape(body: String, expected: Char, what: String) {
        val first = body.trimStart().firstOrNull()
        if (first == expected) return
        val html = PlatformResponseGuard.isHtml(body)
        val blank = body.isBlank()
        val head = body.trim().replace(Regex("\\s+"), " ").take(PlatformResponseGuard.MAX_BODY_CHARS)
            .ifBlank { "空内容" }
        throw PlatformResponseException(
            "$what（HTTP 200，正文开头是「$head」）",
            200,
            unsupported = html || blank,
            html = html,
        )
    }

    private fun executeText(request: Request, allowedCodes: Set<Int> = setOf(200)): String {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            // v0.1.68：反向代理托管的 ComfyUI（百度 AI Studio、CloudStudio 等）不支持的
            // 接口会返回网页——有时是 404 错误页，有时更坑，是 HTTP 200 + 登录页。
            // 以前只校验 HTTP 码，200+HTML 被当成合法 JSON，后面解析抛出莫名其妙的
            // JSONException；而错误时又把整页 HTML 灌进日志，一次 34 行、刷几十次就把
            // 诊断日志彻底淹没。改由 PlatformResponseGuard 按内容统一判定并压缩成一行。
            PlatformResponseGuard.guard(response.code, body, allowedCodes)
            return body
        }
    }

    private fun parseSystemStats(root: JSONObject): SystemStats {
        val system = root.optJSONObject("system") ?: JSONObject()
        val devicesJson = root.optJSONArray("devices") ?: JSONArray()
        val devices = buildList {
            repeat(devicesJson.length()) { index ->
                val item = devicesJson.getJSONObject(index)
                add(DeviceStats(item.optString("name"), item.optLong("vram_total"), item.optLong("vram_free")))
            }
        }
        return SystemStats(
            comfyVersion = system.optString("comfyui_version"),
            frontendVersion = system.optString("required_frontend_version"),
            devices = devices,
        )
    }

    private fun parseQueueArray(array: JSONArray?, state: JobState): List<JobSummary> {
        if (array == null) return emptyList()
        return buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONArray(index) ?: return@repeat
                val id = item.optString(1)
                if (id.isNotBlank()) {
                    add(
                        JobSummary(
                            id = id,
                            state = state,
                            workflowName = workflowName(item.optJSONObject(3)),
                            workflowPath = workflowPath(item.optJSONObject(3)),
                            workflowJson = workflowJson(item.optJSONObject(3)),
                        ),
                    )
                }
            }
        }
    }

    private fun workflowName(extraData: JSONObject?): String =
        extraData?.optJSONObject("comfy_mobile")?.optString("workflow_name").orEmpty()

    /**
     * v0.1.79：解析逻辑搬到 [ExecutionTiming]，和历史列表共用同一条规则（终态优先，
     * execution_cached 只在一条终态都没有时兜底）。以前这里完全不认 execution_cached，
     * "整条链路全是缓存命中"的任务（服务端只发 cached、不发 success）耗时一片空白。
     */
    private fun executionDuration(status: JSONObject?): Long? = ExecutionTiming.durationMs(status)

    private fun workflowPath(extraData: JSONObject?): String =
        extraData?.optJSONObject("comfy_mobile")?.optString("workflow_path").orEmpty()

    private fun workflowJson(extraData: JSONObject?): String? {
        val value = extraData?.optJSONObject("extra_pnginfo")?.opt("workflow") ?: return null
        return when (value) {
            is JSONObject -> value.toString()
            is String -> value
            else -> null
        }
    }

    private fun parseNodeProblems(nodeErrors: JSONObject?): Map<String, List<String>> {
        if (nodeErrors == null) return emptyMap()
        return buildMap {
            nodeErrors.keys().forEach { nodeId ->
                val errors = nodeErrors.optJSONObject(nodeId)?.optJSONArray("errors") ?: return@forEach
                val messages = buildList {
                    repeat(errors.length()) { index ->
                        val item = errors.optJSONObject(index) ?: return@repeat
                        val input = item.optJSONObject("extra_info")?.optString("input_name").orEmpty()
                        val message = when (item.optString("type")) {
                            "required_input_missing" -> "缺少必填输入"
                            "value_not_in_list" -> "所选值不在可用列表中"
                            "value_smaller_than_min" -> "数值低于允许的最小值"
                            "value_bigger_than_max" -> "数值超过允许的最大值"
                            "invalid_input_type" -> "输入类型不正确"
                            "prompt_no_outputs" -> "没有可执行的输出节点"
                            else -> item.optString("message").ifBlank { item.optString("details", "参数校验失败") }
                        }
                        add(if (input.isBlank()) message else "$input：$message")
                    }
                }
                if (messages.isNotEmpty()) put(nodeId, messages.distinct())
            }
        }
    }

    private fun userdataUrl(path: String): String =
        "$baseUrl/userdata/${UserdataPath.encode(path, doubleEncode = doubleEncodedUserdataPath)}"
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        /** 探测服务器最多试几次（含首次）。 */
        const val PROBE_ATTEMPTS = 3
        /** 两次探测之间的等待，下标 0 对应第 2 次尝试前的等待。 */
        val PROBE_RETRY_DELAYS_MS = longArrayOf(1_500L, 3_000L)
        /** 下载时嗅探响应开头的字节数：够看清是不是网页，又不会把整张大图读进内存。 */
        const val SNIFF_BYTES = 2048L
        /** 主动关闭 WebSocket 用的状态码与原因，onClosed 靠它认出"这是自己关的"。 */
        const val NORMAL_CLOSURE = 1000
        const val CLOSE_REASON = "switch server"
    }
}
