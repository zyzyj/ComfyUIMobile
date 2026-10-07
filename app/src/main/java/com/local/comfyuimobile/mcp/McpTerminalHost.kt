package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.model.AiStudioAccount
import com.local.comfyuimobile.model.AiStudioProject
import com.local.comfyuimobile.model.TerminalCommandSafety
import com.local.comfyuimobile.network.AiStudioKernelClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP 侧的终端能力（v0.2.97）。
 *
 * 让 AI 能「输入一条命令、拿到对应输出」——这是它自己启动 ComfyUI、找启动脚本（不靠
 * 硬编码路径）、看日志、探端口的基础。
 *
 * ## 安全模型（这里与界面**不同**，是本项目的一次明确取舍）
 *
 * 界面控制台是「三档判定 + 人工确认」：命令先分级，危险的要用户点确认才发。MCP 的
 * 调用由 AiCode 自主发起、不经过本 App 界面，没有可确认的人——所以模型改成
 * [TerminalCommandSafety.isCatastrophic] 判定：**只拒绝灾难性操作**（`rm -rf /`、
 * 格式化磁盘这类不可逆的），其余一律执行。
 *
 * 用户对此拍了板（F3「AI 完全操控终端」/ F7「只拒绝灾难性操作」/ F10「确认机制不做
 * 在服务端——AiCode 自带授权 UI」）。这不是疏漏，是有意的。
 *
 * ## 与界面的隔离
 *
 * 每个终端名一把锁：AI 的两条命令不会互相插花，也不会与用户在界面上的输入插花。
 * 输出靠**缓冲区快照**（[TerminalShell] 的标记前后对比），不是"读清空"——
 * 因为界面那条终端是共享的：清空会顺手吃掉用户正在看的输出。
 */
internal class McpTerminalHost(
    private val kernel: AiStudioKernelClient,
    /** 取当前绑定的项目；与 AiStudioBridge 不同，终端需要项目才能拿到 endpoint。 */
    private val activeProject: suspend () -> Pair<AiStudioAccount, AiStudioProject>?,
) {

    /** 每个终端名一个输出缓冲；同时作为"这条终端是谁先连的"登记表。 */
    private val buffers = ConcurrentHashMap<String, StringBuilder>()

    /** 每个终端名一把锁：同一终端的命令串行执行（AI 不会自己撞自己）。 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 本次进程内创建的会话名（项目重启后要重新连）。 */
    private val opened = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val tokenCounter = AtomicInteger(0)

    /**
     * 列出可用的终端。
     *
     * 平台侧与本地侧合并：平台返回的是真实存在的 PTY（项目重启后旧的会消失），
     * 本地是已连 WebSocket 的。只信平台会导致"刚建好但还没连"的看不见，
     * 只信本地会导致"平台上有但没连"的看不见。
     */
    suspend fun list(): String {
        val (account, project) = requireContext()
        val endpoint = kernel.fetchEndpoint(account, project.projectId, "")
        val remote = runCatching { kernel.listTerminals(account, endpoint) }
            .getOrElse { error -> throw describe("读取终端列表", error) }
        val local = kernel.sessionNames()
        return buildString {
            if (remote.isEmpty()) {
                appendLine("项目 ${project.projectId} 当前没有终端。用 terminal_exec 时传任意名字会自动新建。")
            } else {
                appendLine("项目 ${project.projectId} 的终端（共 ${remote.size} 个）：")
                remote.forEach { name ->
                    val connected = if (name in local) "已连" else "未连"
                    appendLine("- $name（$connected）")
                }
            }
            append("terminal_exec 的 terminal 参数传这些名字；省略则用 default。")
        }
    }

    /**
     * 执行一条命令并等它跑完（或超时）。
     *
     * @param command 要执行的命令
     * @param terminal 终端名；空 = "default"
     * @param timeoutSeconds 等待上限；到点未完成会返回已有输出 + 提示（交互式命令常见）
     */
    suspend fun exec(command: String, terminal: String?, timeoutSeconds: Int?): String {
        val cmd = command.trim()
        if (cmd.isBlank()) throw IllegalArgumentException("命令为空。")

        // 先过灾难判定：这是 MCP 侧唯一会拒绝命令的地方（见类注释）。
        if (TerminalCommandSafety.isCatastrophic(cmd)) {
            AppLogger.warn("MCP 终端拒绝灾难性命令：${cmd.take(200)}")
            throw IllegalStateException(
                "已拒绝这条命令：被判定为灾难性、不可逆操作（如删除关键路径 / 格式化 / 关机）。" +
                    "如需清理，请改用把目标移到回收目录之类的可逆做法。",
            )
        }

        val name = terminal?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_TERMINAL
        val timeout = (timeoutSeconds ?: TerminalShell.DEFAULT_TIMEOUT_SECONDS).coerceIn(1, 600)

        val buffer = buffers.getOrPut(name) { StringBuilder() }
        val lock = locks.getOrPut(name) { Mutex() }
        // 整条命令（含建会话）都在锁内：AI 的两条命令不会互相插花，也不会与界面上的输入插花；
        // 同时避免两个并发调用各自连一次 WS。
        return lock.withLock {
            val session = ensureSession(name)
            // 快照起点：命令的输出一定在起点之后（工具刚发送它）。
            val startOffset = synchronized(buffer) { buffer.length }
            val token = "t${tokenCounter.incrementAndGet()}x${(System.nanoTime() % 100000).toString(16)}"
            if (!kernel.sendInput(TerminalShell.wrap(cmd, token), session)) {
                throw IllegalStateException("终端 $name 的会话已断开，请重试（会自动重连）。")
            }
            val deadline = System.currentTimeMillis() + timeout * 1000L
            while (true) {
                val snapshot = synchronized(buffer) { buffer.substring(startOffset) }
                val result = TerminalShell.extract(snapshot, token)
                // 用 if/else 表达式返回，而不是 `break <值>`——Kotlin 没有带值的 break。
                if (result.finished) {
                    return@withLock formatOutput(name, cmd, result)
                }
                if (System.currentTimeMillis() >= deadline) {
                    return@withLock formatTimeout(name, timeout, result)
                }
                delay(POLL_MILLIS)
            }
            @Suppress("UNREACHABLE_CODE")
            ""
        }
    }

    /** 读某终端**当前累积的输出**（不清空，用于 tail 日志）。 */
    suspend fun read(terminal: String?, maxLines: Int?): String {
        val name = terminal?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_TERMINAL
        val buffer = buffers[name]
            ?: return "终端 $name 还没有输出（可能尚未连接）。先用 terminal_exec 跑一条命令。"
        val text = synchronized(buffer) { buffer.toString() }
        if (text.isBlank()) return "终端 $name 暂无输出。"
        val lines = text.lines()
        val limit = (maxLines ?: DEFAULT_READ_LINES).coerceIn(1, 2000)
        val shown = if (lines.size <= limit) text else lines.takeLast(limit).joinToString("\n")
        return if (lines.size <= limit) shown else "…（只显示末尾 $limit 行，共 ${lines.size} 行）\n$shown"
    }

    /**
     * 向终端发 Ctrl+C。
     *
     * 超时后 AI 需要能中断（交互式命令如裸 `python` 会一直等）。
     * 走 raw 帧：`\u0003` 是裸字节，补 `\r` 会被当成回车提交。
     */
    suspend fun interrupt(terminal: String?): String {
        val name = terminal?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_TERMINAL
        val session = kernel.session(name)
            ?: return "终端 $name 没有活动会话，无需中断（也不会有命令在跑）。"
        val ok = kernel.sendRawInput("\u0003", session)
        return if (ok) "已向终端 $name 发送 Ctrl+C。" else "发送 Ctrl+C 失败（会话可能刚断开）。"
    }

    // ===== 内部 =====

    /**
     * 取当前运行中的项目（连同账号）。给 wait_for_comfy 自动接入用。
     *
     * 直接转发给 [activeProject]，让调用方不必自己处理异常——取不到就是没项目。
     */
    suspend fun runningProjectOrNull() = runCatching { activeProject() }.getOrNull()

    /** 取项目 endpoint（拿不到时返回 null，不抛）。 */
    suspend fun endpointFor(
        account: AiStudioAccount,
        projectId: String,
    ): AiStudioKernelClient.KernelEndpoint? =
        runCatching { kernel.fetchEndpoint(account, projectId, "") }.getOrNull()

    /** 预热项目级 Cookie（ide-proxy 等）；供 ComfyUI 反代鉴权用。 */
    suspend fun warmUpCookies(
        account: AiStudioAccount,
        endpoint: AiStudioKernelClient.KernelEndpoint,
    ) {
        kernel.warmUpProjectCookies(account, endpoint)
    }

    /** 导出已捕获的 Cookie（含项目级）给 ComfyClient 用。 */
    fun exportCookies(): String = kernel.exportCookies()

    private suspend fun requireContext(): Pair<AiStudioAccount, AiStudioProject> =
        activeProject() ?: throw IllegalStateException(
            "还没有可用的 AI Studio 项目。请先 login（App 的「账号」页）并 start_gpu 启动一个项目，" +
                "然后用 wait_for_comfy 等环境就绪。",
        )

    /**
     * 确保终端 `name` 已连上 WebSocket。
     *
     * 终端会在项目重启后消失，所以每次都先验：本地有会话就直接用；否则探测平台
     * （有就复用，没有就建）再连。**不能只信本地**——本地会话可能是上一个项目留下的。
     */
    private suspend fun ensureSession(name: String): AiStudioKernelClient.TerminalSession {
        val (account, project) = requireContext()
        val existing = kernel.session(name)
        // 已连且是本项目期间连的：直接用。
        if (existing != null && name in opened) return existing

        val endpoint = kernel.fetchEndpoint(account, project.projectId, "")
        val remoteNames = runCatching { kernel.listTerminals(account, endpoint) }
            .getOrElse { error -> throw describe("读取终端列表", error) }
        val finalName = if (name in remoteNames) name else kernel.createTerminal(account, endpoint)
        connect(account, endpoint, finalName)
        opened.add(finalName)
        return kernel.session(finalName)
            ?: throw IllegalStateException("终端 $finalName 连接失败（会话未建立）。")
    }

    /** 连 WebSocket，并把输出追加进该名的缓冲区。 */
    private fun connect(
        account: AiStudioAccount,
        endpoint: AiStudioKernelClient.KernelEndpoint,
        name: String,
    ) {
        val buffer = buffers.getOrPut(name) { StringBuilder() }
        kernel.openTerminal(
            account = account,
            endpoint = endpoint,
            name = name,
            onOutput = { chunk -> synchronized(buffer) { buffer.append(chunk) } },
            onOpen = {
                // locale 修正：平台的 en_US.UTF-8 没生成，中文文件名会被转义成
                // $'\345...' 八进制串，跟界面控制台同一处理。
                kernel.sendInput(TERMINAL_LOCALE_FIX, kernel.session(name))
                kernel.resize(CONSOLE_COLS, CONSOLE_ROWS, kernel.session(name))
            },
            onClosed = { reason ->
                AppLogger.info("MCP 终端 $name 已断开：$reason")
                opened.remove(name)
            },
        )
    }

    private fun formatOutput(
        name: String,
        command: String,
        result: TerminalShell.Result,
    ): String = buildString {
        val code = result.exitCode
        val status = if (code == 0) "成功" else "失败"
        appendLine("终端 $name · 命令：$command")
        appendLine("退出码 $code（$status）")
        if (result.text.isBlank()) {
            append("（无输出）")
        } else {
            appendLine("输出：")
            append(result.text)
        }
    }

    private fun formatTimeout(
        name: String,
        timeoutSeconds: Int,
        partial: TerminalShell.Result,
    ): String = buildString {
        appendLine("终端 $name 的命令在 ${timeoutSeconds} 秒内没有结束。")
        appendLine("常见原因：命令本身就是长时间运行的（如前台跑 ComfyUI），或它进入了交互式界面。")
        if (partial.text.isNotBlank()) {
            appendLine("到目前为止的输出：")
            appendLine(partial.text)
        } else {
            appendLine("（目前为止没有输出）")
        }
        append("如果它不该一直跑：用 terminal_interrupt 发 Ctrl+C；要持续看输出用 terminal_read。")
    }

    /**
     * 把 AI Studio 错误翻译成模型能据以行动的话（与 [AiStudioBridge.describe] 同一条约定）。
     */
    private fun describe(action: String, error: Throwable): Throwable {
        if (error is CancellationException) throw error
        val code = (error as? com.local.comfyuimobile.network.AiStudioException)?.errorCode
        val hint = when {
            com.local.comfyuimobile.network.AiStudioRiskControl.isRetryable(code) ->
                com.local.comfyuimobile.network.AiStudioRiskControl.messageFor(code, null)
            code == 403 -> "登录态或令牌已失效——请在 App 的「账号」页重新登录后重试。"
            else -> null
        }
        val base = "$action 失败：" + (error.message ?: "未知错误")
        return IllegalStateException(if (hint.isNullOrBlank()) base else "$base\n$hint", error)
    }

    internal companion object {
        const val DEFAULT_TERMINAL = "default"
        const val DEFAULT_READ_LINES = 200
        const val POLL_MILLIS = 150L

        /** 与界面控制台一致的终端尺寸（避免输出错行）。 */
        const val CONSOLE_COLS = 120
        const val CONSOLE_ROWS = 40

        /**
         * 与界面控制台**逐字一致**的 locale 修正（见 MainViewModel.TERMINAL_LOCALE_FIX）。
         *
         * 平台的 en_US.UTF-8 在镜像里并未生成，于是 ls 等工具按非 UTF-8 处理，把中文
         * 文件名转义成 $'\345\220\257...' 八进制串。先确认 C.UTF-8 存在再 export，
         * 不存在就保持原样（不要强行设一个不存在的 locale）。
         */
        const val TERMINAL_LOCALE_FIX =
            " locale -a 2>/dev/null | grep -qi '^C\\.UTF-8\$' && " +
                "export LANG=C.UTF-8 LC_ALL=C.UTF-8; true"
    }
}
