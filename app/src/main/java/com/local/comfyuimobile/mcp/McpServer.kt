package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.data.AppLogger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
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
    private val tools: McpToolRegistry,
    private val files: McpFileStore = McpFileStore(),
) {

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "mcp-worker").apply { isDaemon = true }
    }
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
        if (token.isBlank()) return false
        val header = request.headers["authorization"].orEmpty()
        return header == "Bearer $token"
    }

    private fun serveFile(id: String, output: BufferedOutputStream) {
        val entry = files.get(id)
        if (entry == null) {
            output.write(McpHttp.jsonResponse("""{"error":"not found or expired"}""", status = 404))
            return
        }
        output.write(McpHttp.bytesResponse(entry.bytes, entry.contentType))
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

        val result = tools.dispatch(rpc.method, rpc.params, rpc.id)
        if (result == null) {
            // 通知：无 id，回 202 空体（AiCode 按 spec 期望这个形态）。
            if (rpc.id == null) {
                output.write(McpHttp.emptyResponse(202))
            } else {
                val body = McpProtocol.error(
                    rpc.id,
                    McpProtocol.ErrorCode.METHOD_NOT_FOUND,
                    "未知方法：${rpc.method}",
                )
                output.write(McpHttp.jsonResponse(body.toString(), extraHeaders = sessionHeader))
            }
            return
        }
        output.write(McpHttp.jsonResponse(result.toString(), extraHeaders = sessionHeader))
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"

        /** 单次请求读超时：对端连上却不发数据时，不让线程永久占着。 */
        const val READ_TIMEOUT_MILLIS = 30_000
    }
}
