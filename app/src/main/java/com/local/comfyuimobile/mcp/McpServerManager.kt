package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.network.ComfyClient
import java.security.SecureRandom

/**
 * MCP server 的生命周期管理（v0.2.85）。
 *
 * 把"MCP 与 ComfyUI 的接线"收在一处，让 [com.local.comfyuimobile.MainViewModel] 只需调用
 * [start]/[stop]，不必知道端口、token、host 实现等细节。
 *
 * 端口固定 8765：AiCode 侧的配置里要写死这个值，随机端口会让"每次重启都要改配置"。
 * 被占用时启动失败并如实上报，而不是悄悄换一个端口让用户的配置失效。
 */
class McpServerManager(
    private val client: ComfyClient,
    private val currentWorkflowPath: () -> String?,
    private val refreshCookie: suspend () -> Unit,
    private val clientId: String,
) {

    private var server: McpServer? = null

    /** 实际监听端口（未启动为 0）。 */
    val port: Int get() = server?.boundPort ?: 0

    val isRunning: Boolean get() = server?.isRunning == true

    /**
     * 启动服务。
     *
     * @param token 为空时**拒绝启动**——[McpServer] 对空 token 一律返回 401，
     *        与其起一个连不上的服务让用户困惑，不如在设置页明确提示先生成 token。
     * @return 绑定成功的端口；失败抛异常由调用方提示。
     */
    fun start(token: String): Int {
        if (isRunning) return port
        require(token.isNotBlank()) { "缺少访问令牌，请先生成" }
        val files = McpFileStore()
        val registry = McpToolRegistry(
            host = ComfyMcpHost(
                client = client,
                currentWorkflowPath = currentWorkflowPath,
                refreshCookie = refreshCookie,
                clientId = clientId,
            ),
            files = files,
        ) { "http://127.0.0.1:$PORT/files/" }
        val created = McpServer(port = PORT, token = token, tools = registry, files = files)
        created.start()
        server = created
        return created.boundPort
    }

    fun stop() {
        server?.stop()
        server = null
    }

    companion object {
        const val PORT = 8765

        /** 生成一个新的访问令牌（32 字节十六进制，够长且便于复制）。 */
        fun newToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * 生成 AiCode 侧的配置片段，供设置页直接复制。
         *
         * 工具名由 AiCode 拼成 `mcp__comfy__{tool}`；服务器名必须只用
         * `[a-zA-Z0-9_-]`（源码级确证）。
         */
        fun configSnippet(token: String): String = buildString {
            appendLine("{")
            appendLine("  \"mcpServers\": {")
            appendLine("    \"comfy\": {")
            appendLine("      \"url\": \"http://127.0.0.1:$PORT/mcp\",")
            appendLine("      \"headers\": { \"Authorization\": \"Bearer $token\" },")
            appendLine("      \"enabled\": true")
            appendLine("    }")
            appendLine("  }")
            append("}")
        }
    }
}
