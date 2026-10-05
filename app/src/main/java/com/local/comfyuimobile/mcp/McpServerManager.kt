package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.model.ResultMedia
import com.local.comfyuimobile.network.ComfyClient
import java.io.File
import java.security.SecureRandom

/**
 * MCP server 的生命周期管理（v0.2.85；v0.2.88 支持自定义端口）。
 *
 * 把"MCP 与 ComfyUI 的接线"收在一处，让调用方（前台服务）只需 [start]/[stop]，
 * 不必知道端口、token、host 实现等细节。
 *
 * 端口策略（v0.2.88，堵"静默换端口导致客户端配置失效"这个坑）：
 *  - **端口持久化**：用户改过一次就一直用（存偏好）；
 *  - **占用时不静默顺延**：直接抛错，由页面明确告知——顺延等于让 AiCode 那三行
 *    配置悄悄失效，比启动失败更难排查（NeoSQL/ToolHive 都吃过这个亏）；
 *  - 端口可配置后，文件 URL 与配置片段**一律用实际绑定端口**生成。
 */
class McpServerManager(
    private val client: ComfyClient,
    /** 出图落盘目录。必须由外部传入（服务侧拿到的是 Service 的 filesDir）。 */
    private val cacheDir: File,
    private val currentWorkflowPath: () -> String?,
    private val refreshCookie: suspend () -> Unit,
    private val clientId: String,
) {

    private var server: McpServer? = null
    private var files: McpFileStore? = null

    /** 实际监听端口（未启动为 0）。可能因用户配置而不同于 [requestedPort]。 */
    var port: Int = 0
        private set

    val isRunning: Boolean get() = server?.isRunning == true

    /**
     * 启动服务。
     *
     * @param token 为空时**拒绝启动**——[McpServer] 对空 token 一律返回 401，
     *        与其起一个连不上的服务让用户困惑，不如在设置页明确提示先生成 token。
     * @param requestedPort 用户配置的端口。
     * @return 绑定成功的端口；失败抛异常由调用方提示。
     */
    fun start(
        token: String,
        requestedPort: Int = DEFAULT_PORT,
        resultSink: (suspend (ResultMedia, File) -> Unit)? = null,
        onSubmitted: (suspend (promptId: String) -> Unit)? = null,
    ): Int {
        if (isRunning) return port
        require(token.isNotBlank()) { "缺少访问令牌，请先生成" }
        // 每次启动都重建（连同文件登记）：stop 会清掉临时文件，重启后不该还指着它们。
        val store = McpFileStore(cacheDir = File(cacheDir, "mcp_files"))
        val registry = McpToolRegistry(
            host = ComfyMcpHost(
                client = client,
                files = store,
                currentWorkflowPath = currentWorkflowPath,
                refreshCookie = refreshCookie,
                clientId = clientId,
                resultSink = resultSink,
                onSubmitted = onSubmitted,
            ),
            files = store,
        ) { "http://127.0.0.1:$port/files/" }
        val created = McpServer(port = requestedPort, token = token, tools = registry, files = store)
        created.start()
        server = created
        files = store
        port = created.boundPort
        AppLogger.info("MCP server 已绑定 127.0.0.1:$port（请求 $requestedPort）")
        return port
    }

    fun stop() {
        server?.stop()
        server = null
        port = 0
        // 连带清掉出图缓存：里面是完整图片，留着只有风险（服务已停，URL 也没人用了）。
        files?.clear()
        files = null
    }

    companion object {
        /**
         * 默认端口。原 8765 是 Taskshell / MCPDroid 的默认端口，用户群里有人装了
         * 就直接撞——换成一个冷门段的（IANA 未注册动态端口范围中部）。
         */
        const val DEFAULT_PORT = 23456

        /** 端口合法范围（非特权端口）。 */
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        /**
         * 把用户配置解析成实际请求端口。**UI 与服务都必须走这里**。
         *
         * 曾经的 P0：服务侧写了 `stored?.mcpServerPort ?: 0`，而 0 传给
         * `ServerSocket` 的语义是"让系统随机分配"——首次开 MCP（偏好里还是 0）
         * 就会绑到随机端口，UI 与配置片段却显示 23456，AiCode 必然连不上，
         * 且每次重启都换。修正逻辑原本只在 UI 写了一份，服务没同步——
         * 这正是"同一逻辑两处各写一遍"的下场，抽成纯函数让两边没得选。
         */
        fun resolvePort(configured: Int): Int =
            configured.takeIf { it in MIN_PORT..MAX_PORT } ?: DEFAULT_PORT

        /** 生成一个新的访问令牌（24 字节十六进制，够长且便于复制）。 */
        fun newToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * 生成 AiCode 侧的配置片段，供页面直接复制。
         *
         * 工具名由 AiCode 拼成 `mcp__comfy__{tool}`；服务器名必须只用
         * `[a-zA-Z0-9_-]`（源码级确证）。端口用**实际值**——端口可配置后常量不再可靠。
         */
        fun configSnippet(token: String, port: Int): String = buildString {
            appendLine("{")
            appendLine("  \"mcpServers\": {")
            appendLine("    \"comfy\": {")
            appendLine("      \"url\": \"http://127.0.0.1:$port/mcp\",")
            appendLine("      \"headers\": { \"Authorization\": \"Bearer $token\" },")
            appendLine("      \"enabled\": true")
            appendLine("    }")
            appendLine("  }")
            append("}")
        }
    }
}
