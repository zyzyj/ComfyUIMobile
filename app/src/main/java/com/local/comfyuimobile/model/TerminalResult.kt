package com.local.comfyuimobile.model

/**
 * 终端命令的结果模型。
 *
 * 这些类型原在 `AiAssistant.kt` 里，随内置 AI 助手板块一起定义。助手删除后它们
 * **不是**助手专属：`TerminalCommandSafety.sliceOutput` 的返回值、以及"这条命令算不算
 * 失败"的判定（[TerminalCommandResult.noMatch]）都是安全引擎的一部分，未来的
 * `terminal` MCP 工具（把 `TerminalCommandSafety` 从"提议+人工确认"改成"server 自行拒绝"）
 * 同样要用。故抽到独立文件保留，不再依赖助手的对话模型。
 */

/**
 * 从终端缓冲里切出的一条命令的输出与退出码。
 */
data class CommandOutputWindow(
    val output: String,
    val exitCode: Int,
)

/** 一条命令的执行结果。 */
data class TerminalCommandResult(
    val command: String,
    val output: String,
    val exitCode: Int?,
    val success: Boolean,
) {
    /**
     * "无匹配"：`grep` / `rg` 没找到命中项时退出码为 1（POSIX 约定），但这**不是错误**
     * ——"ComfyUI 没在跑"就是守则让 AI 去确认的正常结果（v0.2.83）。
     *
     * 以前统一显示成红字"失败（退出码 1）"，既误导用户、又会被连续失败熔断器计数
     * （AI 连查两次进程就暂停自动执行）。
     *
     * 只对**搜索类命令**生效：`cd /不存在`、`cat 缺失文件` 也会 rc=1 且无输出，
     * 那些是真失败，不能显示成"无匹配"。
     */
    val noMatch: Boolean
        get() = exitCode == 1 && output.isBlank() &&
            TerminalCommandSafety.isMatchSearchCommand(command)

    /**
     * 给**界面**显示的版本（v0.2.66）。
     *
     * 剥掉 ANSI 转义码：真机上 `ls` 的输出带颜色序列，直接显示就是
     * `[0m[01;36mComfyUI[0m` 这种乱码。显示时也要截断——终端输出可能有几万行。
     */
    fun forDisplay(maxChars: Int = 4_000): String {
        val clean = AnsiText.tidy(output)
        val body = if (clean.length <= maxChars) clean else clean.take(maxChars) + "\n…（输出过长，已截断）"
        return "${statusLabel()}\n${body.ifBlank { "（无输出）" }}"
    }

    /** 状态的文案（v0.2.83）：无匹配不算失败。 */
    private fun statusLabel(): String = when {
        noMatch -> "无匹配"
        exitCode == null -> "未捕获到退出码"
        exitCode == 0 -> "成功"
        else -> "失败（退出码 $exitCode）"
    }

    /** 喂回模型时的紧凑表示：太长会挤爆上下文，截断尾部（错误通常出现在尾部）。 */
    fun forModel(maxChars: Int = 2_000): String {
        // 同样先剥 ANSI：颜色码对模型毫无意义，只会平白多占 token。
        val trimmed = AnsiText.tidy(output)
        val body = if (trimmed.length <= maxChars) {
            trimmed
        } else {
            "…（前部省略）\n" + trimmed.takeLast(maxChars)
        }
        val status = when {
            noMatch -> "无匹配（这不代表出错）"
            exitCode == null -> "（未捕获到退出码）"
            exitCode == 0 -> "成功（exit=0）"
            else -> "失败（exit=$exitCode）"
        }
        return "$ $command\n$status\n${body.ifBlank { "（无输出）" }}"
    }
}
