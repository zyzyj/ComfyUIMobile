package com.local.comfyuimobile.mcp

/**
 * `terminal_exec` 的后台执行包装（v0.3.7）。纯函数，可单测。
 *
 * ## 为什么需要
 *
 * 真机实证（AiCode MCP 日志 10-09）：一条 `terminal_exec` 跑了 34 分钟后超时，
 * **AiCode 的 agent 循环直接终止了整轮任务**——不重试、不继续，剩下 39 个任务
 * 一个都没跑。原因不在本 App：`terminal_exec` 是同步阻塞的（上限 600 秒），
 * 而 AiCode 侧的后台任务通知只对它自己的本地容器生效。
 *
 * 所以长任务必须「提交完立刻返回、让 AI 继续做别的」。这里用 `nohup` + 重定向
 * 到日志文件实现：命令在远端继续跑，输出**同时**留在日志里，AI 用
 * `terminal_read` 或 `tail` 看进度。
 *
 * ## 为什么不用 `&` 结尾的朴素写法
 *
 * AI 自己写 `cmd &` 时，PTY 会把它放进后台，但一旦终端会话断开（反代约 2 分钟
 * 掐一次 WebSocket），子进程会收到 SIGHUP 被杀掉。`nohup` 正是为此存在。
 */
internal object TerminalBackground {

    /** 后台任务的日志目录（放 /tmp，避免污染用户的 work 目录）。 */
    const val LOG_DIR = "/tmp/mcp-bg"

    /**
     * 把命令包成后台执行形式。
     *
     * @param command 用户命令
     * @param logPath 输出重定向到的日志文件
     */
    fun wrap(command: String, logPath: String): String {
        val cmd = command.trim().trimEnd('&').trimEnd()
        // nohup + 重定向：断开连接不杀进程，输出留在文件里。
        // `setsid` 让它在新的会话里跑，进一步与 PTY 解耦（没有 setsid 时退回 nohup）。
        return "mkdir -p $LOG_DIR && nohup setsid bash -c ${shellQuote(cmd)} > $logPath 2>&1 & echo \$!"
    }

    /** 日志文件路径：按终端名 + 时间戳，避免互相覆盖。 */
    fun logPathFor(terminal: String, timestamp: Long): String {
        val safe = terminal.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "default" }
        return "$LOG_DIR/$safe-$timestamp.log"
    }

    /**
     * 单引号包裹，内部单引号用 `'\''` 转义。
     *
     * **这是安全边界**：命令直接来自模型，若不转义，命令里的 `"` `$` 会在包裹层
     * 被外层 shell 展开——轻则命令跑错，重则形成命令注入。
     */
    fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /**
     * 从后台启动的结果里解析 PID。
     *
     * 输出形如 `12345\r\n`（echo $! 的结果，PTY 可能带回车）。拿不到返回 null——
     * 不要编一个假 PID。
     */
    fun parsePid(output: String): Long? =
        output.replace("\r", "\n").lines().firstNotNullOfOrNull { it.trim().toLongOrNull() }
}
