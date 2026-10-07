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
     * 是否启用 Bearer 鉴权（v0.2.97，用户决策 F1：默认免鉴权）。
     *
     * 刻意用**显式参数**而不是"token 为空即免鉴权"：后者会在"忘了生成 token"
     * 时意外开一个无凭证入口，而错误与正常两种意图无法区分。
     *
     * 免鉴权的前提是**只绑 127.0.0.1**（本机其他 App 可访问，局域网不可）。
     */
    private val requireAuth: Boolean,
    private val tools: McpToolRegistry,
    private val files: McpFileStore,
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
            // bind 到 loopback：第三个参数 backlog，第四个是绑定地址。
            val socket = ServerSocket(port, 16, InetAddress.getByName(LOOPBACK))
            serverSocket = socket
            boundPort = socket.localPort
            AppLogger.info("MCP server 已启动：http://$LOOPBACK:$boundPort/mcp")
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
            when (val read = McpHttp.readRequest(input)) {
                is HttpReadResult.Bad -> output.write(
                    McpHttp.jsonResponse(
                        """{"error":${JSONObject.quote(read.reason)}}""",
                        status = read.status,
                    ),
                )
                is HttpReadResult.Ok -> route(read.request, output)
            }
            output.flush()
        }
    }

    private suspend fun route(request: HttpRequest, output: BufferedOutputStream) {
        val path = request.path
        if (request.method == "GET" && path.startsWith("/files/")) {
            serveFile(path.removePrefix("/files/"), output)
            return
        }
        if (request.method == "GET" && (path == "/health" || path == "/")) {
            output.write(McpHttp.jsonResponse("""{"ok":true}"""))
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
        if (!authorized(request)) {
            output.write(McpHttp.jsonResponse("""{"error":"unauthorized"}""", status = 401))
            return
        }
        handleRpc(request, output)
    }

    /**
     * Bearer 鉴权。token 为空时**不放行**（防配置疏漏导致无鉴权裸奔）——
     * 宁可服务起不来，也不给一个无凭证入口。
     */
    private fun authorized(request: HttpRequest): Boolean {
        // 免鉴权模式（F1）：只绑 loopback，本机其他 App 可访问、局域网不可。
        if (!requireAuth) return true
        if (token.isBlank()) return false
        val header = request.headers["authorization"].orEmpty()
        return header == "Bearer $token"
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
            output.write(McpHttp.jsonResponse("""{"error":"not found or expired"}""", status = 404))
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

    private suspend fun handleRpc(request: HttpRequest, output: BufferedOutputStream) {
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
        val result = tools.dispatch(rpc.method, rpc.params, rpc.id)
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
