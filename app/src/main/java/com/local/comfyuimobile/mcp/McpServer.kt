package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.data.AppLogger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内嵌的 MCP server（v0.2.85）。
 *
 * 让同机的 AiCode（Android AI 编程 Agent）经 `http://127.0.0.1:{port}/mcp` 操作
 * 本 App 连接着的 ComfyUI。协议形态全部来自对 AiCode 客户端的源码核对与实测。
 *
 * 两个端点：
 *  - `POST /mcp`      JSON-RPC（initialize / tools/list / tools/call / 通知）
 *  - `GET  /files/{id}` 出图字节流（AiCode 侧 `curl` 后交给它的 viewImage）
 *
 * 设计约束：
 *  - **只绑 127.0.0.1**。绑 `0.0.0.0` 等于把"能操作你 ComfyUI 的接口"暴露给同一
 *    WiFi 的任何人——loopback 是这套方案唯一的安全前提。
 *  - **无状态**。AiCode 是一问一答、切后台不重连；服务端不维护会话，缺失或过期的
 *    `Mcp-Session-Id` 一律当普通请求处理（带上就回显，仅此而已）。
 *  - **线程模型**：accept 一个线程，每个连接一个线程。MCP 是低频调用，不值得上线程池
 *    调优；但必须有上限，否则被扫端口时线程数无界增长。
 */
internal class McpServer(
    private val port: Int,
    private val token: String,
    /**
     * 是否对**局域网来源**启用 Bearer 鉴权（v0.3.20）。
     *
     * 鉴权模型（v0.3.20 重构）：
     *  - **本机 loopback（127.0.0.1 / ::1）永远免鉴权**——loopback 只有本机 App 能连，
     *    这是整套方案的安全前提，没必要让同机 AiCode 也带 token。
     *  - **局域网来源（非 loopback）才看本开关**：true = 要 token，false = 免鉴权但降级
     *    为「只许出图 + 取图」的受限权限（禁终端/GPU/账号，见 [McpToolRegistry]）。
     *
     * 刻意用**显式参数**而不是"token 为空即免鉴权"：后者会在"忘了生成 token"
     * 时意外开一个无凭证入口，而错误与正常两种意图无法区分。
     */
    private val requireAuth: Boolean,
    /**
     * 绑定地址（v0.3.15）。默认 `127.0.0.1`（仅本机）；局域网模式传 `0.0.0.0`。
     */
    private val bindAddress: String = "127.0.0.1",
    private val tools: McpToolRegistry,
    private val files: McpFileStore,
    /**
     * 判断客户端是否本机 loopback（v0.3.20，测试注入点）。
     * 默认用真实 socket 地址的 `isLoopbackAddress`；测试可注入假判断以模拟局域网来源。
     */
    private val isLoopbackClient: (InetAddress) -> Boolean = { it.isLoopbackAddress },
    /**
     * 取 ComfyUI 服务器基址（v0.3.29）。空串 = 未连接，此时 /view 返回 503。
     *
     * 用于把 ComfyUI 自己的 /view、/history 转发出去——真机反馈：云端终端内
     * curl 127.0.0.1:8188/view 返回 200 / 1.36MB，但手机侧 23456 的 /view 是 404，
     * 因为本 server 原本只路由 /mcp 与 /files/。
     */
    private val comfyBaseUrl: () -> String = { "" },
    /** 本 App 版本名（v0.3.30），用于 /health 自报——排查「装了旧包」时一眼可见。 */
    private val appVersion: String = "",
) {

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    /**
     * 工作线程池。**每次 [start] 都重建**——[stop] 会 `shutdownNow()`，而它是被
     * 关闭后不可复用的：若不重建，第二次启动能绑上端口、accept 线程也能起，
     * 但第一个连接进来时 `execute` 就抛 `RejectedExecutionException`，accept 线程
     * 当场死掉，之后所有连接超时。界面却显示"已启动"——静默失败，最难查的那种。
     * 「重新生成令牌」正是 stop → start，属设置页常规按钮。
     */
    private var workers: ExecutorService = newWorkerPool()
    /** 正在处理的连接。`stop()` 要把它们一并关掉，否则卡在 read 的线程会永远留着。 */
    private val liveSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()

    /** 实际绑定端口（port 传 0 时由系统分配，单测用）。 */
    @Volatile
    var boundPort: Int = -1
        private set

    val isRunning: Boolean get() = running.get()

    /** 启动监听。绑定失败（端口占用）抛异常，由调用方决定是否提示用户。 */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        workers = newWorkerPool()
        try {
            // 第三个参数 backlog，第四个是绑定地址。
            // v0.3.15：绑定地址改为可注入（loopback 或 0.0.0.0）。
            val socket = ServerSocket(port, 16, InetAddress.getByName(bindAddress))
            serverSocket = socket
            boundPort = socket.localPort
            AppLogger.info("MCP server 已启动：http://$bindAddress:$boundPort/mcp")
        } catch (error: Throwable) {
            running.set(false)
            throw error
        }
        val acceptThread = Thread({ acceptLoop() }, "mcp-accept").apply { isDaemon = true }
        acceptThread.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        // 正在处理的连接必须一并关掉：socket 的 `read()` **不响应线程中断**
        // （`Thread.interrupt()` 对套接字流无效），只 shutdownNow 的话，卡在读
        // 请求上的 worker 会永远留在那里——本套测试首次跑就把 CI 拖到 40 分钟超时。
        liveSockets.forEach { runCatching { it.close() } }
        liveSockets.clear()
        workers.shutdownNow()
        AppLogger.info("MCP server 已停止")
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                serverSocket?.accept() ?: break
            } catch (e: SocketException) {
                break // stop() 关掉了 serverSocket
            } catch (error: Throwable) {
                if (!running.get()) break
                AppLogger.warn("MCP 接受连接失败", error)
                continue
            }
            workers.execute {
                runCatching { runBlocking { handle(socket) } }
                    .onFailure { AppLogger.warn("MCP 请求处理失败", it) }
            }
        }
    }

    private suspend fun handle(socket: Socket) {
        liveSockets += socket
        try {
            handleInner(socket)
        } finally {
            liveSockets -= socket
        }
    }

    private suspend fun handleInner(socket: Socket) {
        socket.use { s ->
            s.soTimeout = READ_TIMEOUT_MILLIS
            val input = s.getInputStream()
            val output = BufferedOutputStream(s.getOutputStream())
            // v0.3.20：记录客户端来源地址，用于 loopback/局域网鉴权与权限分级。
            val clientAddress = (s.inetAddress ?: InetAddress.getLoopbackAddress())
            when (val read = McpHttp.readRequest(input)) {
                is HttpReadResult.Bad -> output.write(
                    McpHttp.jsonResponse(
                        """{"error":${JSONObject.quote(read.reason)}}""",
                        status = read.status,
                    ),
                )
                is HttpReadResult.Ok -> route(read.request, clientAddress, output)
            }
            output.flush()
        }
    }

    private suspend fun route(request: HttpRequest, clientAddress: InetAddress, output: BufferedOutputStream) {
        val path = request.path
        if (request.method == "GET" && path.startsWith("/files/")) {
            serveFile(path.removePrefix("/files/"), output)
            return
        }
        // v0.3.29（真机反馈）：转发 ComfyUI 自己的 /view 与 /history。
        //
        // 用户实测：云端终端内 curl 127.0.0.1:8188/view 返回 200 / 1.36MB，
        // 但 http://<手机IP>:23456/view?filename=... 是 404——因为**本 server 只路由
        // 了 /mcp 与 /files/，其他一律 404**（不是“反代没配”）。
        //
        // 转发价值：拿到 ComfyUI 原生原图（字节级一致），不必经过 generate 登记，
        // 也就不必为了拿图重渲染（用户为此多烧了 5 张算力卡）。
        if (request.method == "GET" && (path.startsWith("/view") || path.startsWith("/history"))) {
            proxyToComfy(request, output)
            return
        }
        if (request.method == "GET" && (path == "/health" || path == "/")) {
            // v0.3.30：带上版本号与已注册路由。
            //
            // 真机反馈里「/view 还 404」反复出现，而代码里确实有这条路由——
            // 最常见的原因是**装的是旧包**。让 /health 直接吐版本，一眼就能确认，
            // 不必再靠“应该已经修了吧”去猜。
            output.write(
                McpHttp.jsonResponse(
                    """{"ok":true,"version":${JSONObject.quote(appVersion)},"routes":["/mcp","/files/*","/view","/history","/health"],"comfyConnected":${comfyBaseUrl().isNotBlank()}}""",
                ),
            )
            return
        }
        if (path != "/mcp") {
            output.write(McpHttp.jsonResponse("""{"error":"not found"}""", status = 404))
            return
        }
        if (request.method != "POST") {
            output.write(McpHttp.jsonResponse("""{"error":"use POST"}""", status = 405))
            return
        }
        if (!authorized(request, clientAddress)) {
            output.write(McpHttp.jsonResponse("""{"error":"unauthorized"}""", status = 401))
            return
        }
        handleRpc(request, clientAddress, output)
    }

    /**
     * Bearer 鉴权（v0.3.20 重构）。
     *
     * 规则：
     *  - 本机 loopback 来源 → 永远放行（免鉴权）。
     *  - 局域网来源 → 看 [requireAuth]：true 校验 token，false 放行（但权限降级）。
     */
    private fun authorized(request: HttpRequest, clientAddress: InetAddress): Boolean {
        if (isLoopbackClient(clientAddress)) return true
        if (!requireAuth) return true
        if (token.isBlank()) return false
        val header = request.headers["authorization"].orEmpty()
        return header == "Bearer $token"
    }

    /**
     * 判断当前连接是否受限（v0.3.20）：局域网来源 + 免鉴权。
     * 这类客户端只能出图/取图，禁止终端、GPU、账号管理等高危操作。
     */
    private fun restricted(clientAddress: InetAddress): Boolean =
        !isLoopbackClient(clientAddress) && !requireAuth

    /**
     * 把 ComfyUI 自己的 /view、/history 转发出去（v0.3.29）。
     *
     * 为什么需要：真机反馈说手机侧 `/view` 是 404，误以为是反代没配——实际是本
     * server 只路由了 `/mcp` 与 `/files/`，其余一律 404。ComfyUI 自己的 /view 是好
     * 的（云端 curl 返回 200 / 1.36MB），只是没被转发。
     *
     * 价值：拿到字节级原图，不必经 generate 登记——也就不必为了取图而重渲染
     * （真机反馈：为此多烧了 5 张算力卡）。
     */
    private fun proxyToComfy(request: HttpRequest, output: BufferedOutputStream) {
        val base = comfyBaseUrl().trimEnd('/')
        if (base.isBlank()) {
            output.write(
                McpHttp.jsonResponse(
                    """{"error":"comfy not connected","hint":"未连接 ComfyUI，请先在 App 里连接服务器或调 wait_for_comfy"}""",
                    status = 503,
                ),
            )
            return
        }
        // 用客户端原样的 target（含 query），拼到 ComfyUI 基址后面。
        val url = base + request.target
        val ok = runCatching {
            // 复用同一个 OkHttp 实例？不——这里只需要 GET 且要流式写响应，
            // 直接用 URL 打开即可（ComfyClient 的拦截器还会带 Cookie，但 /view 不需要）。
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.requestMethod = "GET"
            conn.inputStream.use { input ->
                val type = conn.contentType ?: "application/octet-stream"
                output.write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: $type\r\n" +
                            "Content-Length: ${conn.contentLengthLong}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(),
                )
                input.copyTo(output, bufferSize = 64 * 1024)
            }
            true
        }.getOrElse { error ->
            AppLogger.warn("转发 $url 失败", error)
            false
        }
        if (!ok) {
            runCatching {
                output.write(
                    McpHttp.jsonResponse(
                        """{"error":"proxy failed","hint":"无法从 ComfyUI 取到该资源（服务未就绪或路径不对）"}""",
                        status = 502,
                    ),
                )
            }
        }
    }

    /**
     * 发送出图文件。
     *
     * **流式读盘**，不把内容搬进内存：服务要在后台常驻，而一张图 2~10 MB、
     * 每小时多次调用，堆里堆着这些字节会显著抬高被杀概率（见 [McpFileStore] 的说明）。
     */
    private fun serveFile(id: String, output: BufferedOutputStream) {
        val entry = files.get(id)
        if (entry == null) {
            McpCallLog.log("GET /files/${id.take(8)}…", ok = false, detail = "不存在或已过期")
            // 清单 §九 N1：过期要说明白，不能只给一个 404 让人以为是路径写错了。
            // v0.3.26（外部报告 3.4）：文案原来写「1 小时」，而 TTL 早已在 v0.3.22 改为
            // 24 小时——错误的有效期会把排查带偏（以为是过期，实际是进程重启清空）。
            // 两种真实失效原因都写进去：过期 24h / 服务重启被 clear()。
            output.write(
                McpHttp.jsonResponse(
                    """{"error":"not found or expired","hint":"文件不存在或已失效（URL 有效期 24 小时；MCP 服务重启会清空全部已发出 URL）。请重新 generate 或重新取图，不要重复请求同一 URL。"}""",
                    status = 404,
                ),
            )
            return
        }
        McpCallLog.log(
            "GET /files/${id.take(8)}…",
            ok = true,
            detail = "${entry.filenameDisplay()} ${formatBytes(entry.size)}",
        )
        output.write(McpHttp.fileHeader(entry.size, entry.contentType))
        runCatching { entry.file.inputStream().use { it.copyTo(output, FILE_COPY_BUFFER) } }
            .onFailure { AppLogger.warn("MCP 发送文件失败：${entry.file.name}", it) }
    }

    private suspend fun handleRpc(request: HttpRequest, clientAddress: InetAddress, output: BufferedOutputStream) {
        val rpc = McpProtocol.parseRequest(request.body)
        if (rpc == null) {
            output.write(
                McpHttp.jsonResponse(
                    McpProtocol.error(null, McpProtocol.ErrorCode.PARSE_ERROR, "JSON 解析失败").toString(),
                ),
            )
            return
        }

        // 回显客户端带来的 session id（若它带了）。服务端不留状态——只是把同一个值
        // 回给它，让走 spec 会话路径的客户端也自洽。
        val sessionHeader = request.headers["mcp-session-id"]
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf("Mcp-Session-Id" to it) }
            .orEmpty()

        val startedAt = System.currentTimeMillis()
        // v0.3.20：权限分级——局域网免鉴权的连接只能出图/取图，禁终端/GPU/账号。
        val result = tools.dispatch(rpc.method, rpc.params, rpc.id, restricted = restricted(clientAddress))
        val elapsed = System.currentTimeMillis() - startedAt
        if (result == null) {
            // 通知：无 id，回 202 空体（AiCode 按 spec 期望这个形态）。通知不进日志
            // ——它没有结果，每次握手都记一条只会淹没真正的调用。
            if (rpc.id == null) {
                output.write(McpHttp.emptyResponse(202))
            } else {
                McpCallLog.log(rpc.method, ok = false, detail = "未知方法（${elapsed}ms）")
                val body = McpProtocol.error(
                    rpc.id,
                    McpProtocol.ErrorCode.METHOD_NOT_FOUND,
                    "未知方法：${rpc.method}",
                )
                output.write(McpHttp.jsonResponse(body.toString(), extraHeaders = sessionHeader))
            }
            return
        }
        // 工具调用记一行（成功带耗时；失败带原因——isError 与 JSON-RPC error 都算失败）。
        val failed = result.optBoolean("isError", false) || result.has("error")
        McpCallLog.log(rpc.method, ok = !failed, detail = "${elapsed}ms")
        output.write(McpHttp.jsonResponse(result.toString(), extraHeaders = sessionHeader))
    }

    /** 日志用的人读字节大小。 */
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1fMB".format(bytes / 1048576.0)
        bytes >= 1 shl 10 -> "%.1fKB".format(bytes / 1024.0)
        else -> "${bytes}B"
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        /** 单次请求读超时：对端连上却不发数据时，不让线程永久占着。 */
        const val READ_TIMEOUT_MILLIS = 30_000

        /** 并发处理上限。backlog=16 只限制等待 accept 的队列，不限制 worker 数量。 */
        const val MAX_WORKERS = 8

        /** 流式发送文件时的拷贝缓冲。16KB 是磁盘顺序读的常见最优粒度。 */
        const val FILE_COPY_BUFFER = 16 * 1024

        /**
         * 有上限的线程池：空闲线程 60 秒回收，超过 [MAX_WORKERS] 的请求排队。
         *
         * 本项目只服务本机一个客户端，正常最多同时一两个连接；设上限是为了
         * 万一被扫端口时线程数不无界增长（`newCachedThreadPool` 没有上限）。
         */
        fun newWorkerPool(): ExecutorService = ThreadPoolExecutor(
            0,
            MAX_WORKERS,
            60L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            { runnable -> Thread(runnable, "mcp-worker").apply { isDaemon = true } },
        )
    }
}
