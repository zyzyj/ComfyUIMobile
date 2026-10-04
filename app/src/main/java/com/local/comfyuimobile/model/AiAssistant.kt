package com.local.comfyuimobile.model

/**
 * AI 助手板块（v0.2.54）：一个能读终端、能提议命令的对话界面。
 *
 * 设计边界（刻意不做的事）：
 *  - **不让 AI 直接执行命令**。模型只输出命令建议，由 [TerminalCommandSafety] 判定
 *    风险等级后在界面上展示，用户确认才发送。理由见 TerminalCommandSafety 的注释。
 *  - **不做"AI 搭工作流"**。工作流是有向图，文本模型搭出来连线与参数错误率高，
 *    且验证成本极高（要跑一遍才知道错）；App 已有 ComfyUI 原生画布可用。
 *
 * 能可靠做的两件事：
 *  1. 帮你写提示词（已存在于参数页/快捷页的 AI 图标，本页也提供入口）
 *  2. 帮你管终端：下载插件、找模型、看显存、装依赖——读输出后判断下一步
 */
enum class TerminalMessageRole {
    USER,
    ASSISTANT,

    /** 系统旁注（排队提示、压缩提示、报错说明）。居中细灰字，不是"谁说的话"。 */
    SYSTEM_NOTE,
}

/**
 * 一条对话消息（v0.2.83 重构）。
 *
 * 以前 `EXECUTED_MARK` 与 `TERMINAL_OUTPUT` 是两个独立角色，于是**一次命令执行会
 * 变成两条额外消息**散在对话流里（标记气泡 + 输出气泡），而待确认卡片又在列表外
 * ——用户得自己把三处拼成一个逻辑单元。
 *
 * 现在工具调用内联在消息上（[toolCalls]），状态原地迁移；角色只剩三类：
 * 谁说的话（USER/ASSISTANT）与旁注（SYSTEM_NOTE）。
 */
data class TerminalChatMessage(
    val id: String,
    val role: TerminalMessageRole,
    val text: String,
    val commands: List<String> = emptyList(),
    /** 本条消息触发的工具调用（v0.2.83）。内联渲染在消息正文之后。 */
    val toolCalls: List<ToolCall> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * 从终端缓冲里切出的一条命令的输出与退出码（v0.2.61）。
 */
data class CommandOutputWindow(
    val output: String,
    val exitCode: Int,
)

/** 一条命令的执行结果，会作为后续对话的上下文。 */
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
     * 判定：退出码为 1 **且**没有任何输出。有输出时即使 rc=1 也可能是真错误，不归进这。
     */
    val noMatch: Boolean
        get() = exitCode == 1 && AnsiText.tidy(output).isBlank()

    /**
     * 给**界面**显示的版本（v0.2.66）。
     *
     * 与 [forModel] 的区别：剥掉 ANSI 转义码。真机上 `ls` 的输出带颜色序列，
     * 直接显示就是 `[0m[01;36mComfyUI[0m` 这种乱码。
     * 显示时也要截断——终端输出可能有几万行，全部塞进列表会卡。
     */
    fun forDisplay(maxChars: Int = 4_000): String {
        val clean = AnsiText.tidy(output)
        val body = if (clean.length <= maxChars) clean else clean.take(maxChars) + "\n…（输出过长，已截断）"
        val status = statusLabel()
        // 刻意**不回显命令**：界面里紧接着上面就是"（已执行）xxx"的气泡，
        // 再写一遍 `$ xxx` 是重复（真机截图里能看到同一行出现两次）。
        // 命令本身仍留在 [command] 字段里，喂给模型的版本（forModel）照旧带上。
        return "$status\n${body.ifBlank { "（无输出）" }}"
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
