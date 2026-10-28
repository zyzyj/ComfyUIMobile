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
 *
 * v0.2.97：改为 `internal`。新增的 AI Studio 通道参数是 internal 类型
 * （[AiStudioBridge]），而 Kotlin 不允许公开 API 暴露 internal 类型；本模块只有一个
 * `:app`，没有外部调用方，收窄是零成本的。
 */
internal class McpServerManager(
    private val client: ComfyClient,
    /** 出图落盘目录。必须由外部传入（服务侧拿到的是 Service 的 filesDir）。 */
    private val cacheDir: File,
    private val currentWorkflowPath: () -> String?,
    private val refreshCookie: suspend () -> Unit,
    private val clientId: String,
    /** AI Studio 通道（v0.2.97）。null = 不启用，相关工具会返回明确提示。 */
    private val aiStudio: AiStudioBridge? = null,
    /** 终端能力（v0.2.97）。null = 不启用。 */
    private val terminal: McpTerminalHost? = null,
    /**
     * 读「已提交任务记录」（v0.3.7）。与 [onSubmitted] 写的是同一份存储。
     * null = list_my_jobs 不可用（相关调用会返回明确提示）。
     */
    private val submittedJobsReader: (suspend () -> List<com.local.comfyuimobile.data.SubmittedJobRecord>)? = null,
    /** 把任务标为「已取图」（v0.3.7）。null = 不标记。 */
    private val submittedJobFetched: (suspend (String) -> Unit)? = null,
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
     * @param bindAddress 绑定地址（默认 `127.0.0.1`；局域网模式传 `0.0.0.0`）。
     *        绑 `0.0.0.0` 时 [requireAuth] 必须为 true，否则拒绝启动。
     * @return 绑定成功的端口；失败抛异常由调用方提示。
     */
    fun start(
        token: String,
        requestedPort: Int = DEFAULT_PORT,
        /** 是否启用 Bearer 鉴权（F1：默认 false = 免鉴权）。 */
        requireAuth: Boolean = false,
        bindAddress: String = LOOPBACK,
        resultSink: (suspend (ResultMedia, File) -> Unit)? = null,
        onSubmitted: (suspend (promptId: String) -> Unit)? = null,
    ): Int {
        if (isRunning) return port
        // v0.3.18（产品决策）：局域网**不再强制鉴权**。
        //
        // 早期（v0.3.15~v0.3.17）把「局域网 + 免鉴权」当硬约束拒绝启动，结果反而
        // 造成两个更糟的问题：① 用户关鉴权时服务拒绝启动、UI 卡死横跳；② 存量脏数据
        // （LAN=true + auth=false）让滑动后台重进后自动拉起失败。
        //
        // 现在的边界：局域网 + 免鉴权 = 同一 WiFi 下任何人可调 terminal_exec（任意命令
        // 执行）。这是真实风险，但属用户自担的设备/局域网场景；UI 上会给出醒目警告，
        // 把选择权交给用户，而不是替用户把服务掐死。
        val lan = bindAddress == LAN_ALL
        if (lan && !requireAuth) {
            AppLogger.warn(
                "MCP 以「局域网 + 免鉴权」启动：同一 WiFi 下任何设备都能调 terminal_exec 执行任意命令。" +
                    "这是用户明确选择的低安全配置。",
            )
        }
        // 启用鉴权时才必须有 token；免鉴权模式不需要（也就不该因缺 token 而拒绝启动）。
        require(!requireAuth || token.isNotBlank()) { "已开启鉴权，请先生成访问令牌" }
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
                aiStudio = aiStudio,
                terminal = terminal,
                submittedJobsReader = submittedJobsReader,
                submittedJobFetched = submittedJobFetched,
            ),
            files = store,
        ) {
            // v0.3.15：文件 URL 的 host 必须与**客户端实际连接用的地址**一致。
            // 局域网模式下若仍拼 127.0.0.1，电脑拿到的 URL 指向它自己，必然 404
            // （且日志干净，极难查）。用本机首个 IPv4 地址；取不到就回退 loopback。
            val host = if (lan) com.local.comfyuimobile.network.LocalNetwork.primaryIpv4() ?: LOOPBACK else LOOPBACK
            "http://$host:$port/files/"
        }
        val created = McpServer(
            port = requestedPort,
            token = token,
            requireAuth = requireAuth,
            bindAddress = bindAddress,
            tools = registry,
            files = store,
        )
        created.start()
        server = created
        files = store
        port = created.boundPort
        AppLogger.info("MCP server 已绑定 $bindAddress:$port（请求 $requestedPort）")
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

    internal companion object {
        /**
         * 默认端口。原 8765 是 Taskshell / MCPDroid 的默认端口，用户群里有人装了
         * 就直接撞——换成一个冷门段的（IANA 未注册动态端口范围中部）。
         */
        const val DEFAULT_PORT = 23456

        /** 端口合法范围（非特权端口）。 */
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        /** 只绑本机（免鉴权的前提）。 */
        const val LOOPBACK = "127.0.0.1"

        /** 绑所有网卡（局域网可达；必须配合鉴权）。 */
        const val LAN_ALL = "0.0.0.0"

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
         * v0.2.97：默认**免鉴权**（用户决策 F1），因此默认不带 `headers`——
         * 这也回避了一个实际问题：AiCode 的图形配置界面只能填 URL，
         * token 没地方填。仅当用户主动开启鉴权时才给出 headers。
         *
         * v0.3.15：支持局域网地址——[lanHost] 非空时用它替代 127.0.0.1。
         * 局域网模式强制鉴权（见 [start]），所以这里必然带 headers。
         *
         * 工具名由 AiCode 拼成 `mcp__comfy__{tool}`；服务器名必须只用
         * `[a-zA-Z0-9_-]`（源码级确证）。端口用**实际值**。
         */
        fun configSnippet(
            token: String,
            port: Int,
            requireAuth: Boolean = false,
            lanHost: String? = null,
        ): String = buildString {
            val host = lanHost?.takeIf { it.isNotBlank() } ?: LOOPBACK
            appendLine("{")
            appendLine("  \"mcpServers\": {")
            appendLine("    \"comfy\": {")
            appendLine("      \"url\": \"http://$host:$port/mcp\",")
            if (requireAuth && token.isNotBlank()) {
                appendLine("      \"headers\": { \"Authorization\": \"Bearer $token\" },")
            }
            appendLine("      \"enabled\": true")
            appendLine("    }")
            appendLine("  }")
            append("}")
        }
    }
}
