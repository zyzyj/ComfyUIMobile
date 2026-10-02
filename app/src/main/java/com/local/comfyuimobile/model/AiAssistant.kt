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

    /**
     * 命令跑出来的终端输出（v0.2.66 从 SYSTEM_NOTE 拆出来）。
     *
     * 以前和系统旁注共用一种角色，于是**终端输出也被居中小字渲染**——
     * 多行输出挤成一团、`ls` 的列对齐全乱（真机截图可见）。它和"旁注"是两种东西：
     * 这是命令的真实结果，该左对齐、等宽、可横向滚动。
     */
    TERMINAL_OUTPUT,
}

/**
 * 一条对话消息。
 *
 * [commands] 是模型回复里解析出的命令建议；界面据此渲染"执行/跳过"按钮。
 * [commandResults] 记录某条命令实际跑出来的结果，供下一轮喂回模型。
 */
data class TerminalChatMessage(
    val id: String,
    val role: TerminalMessageRole,
    val text: String,
    val commands: List<String> = emptyList(),
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
     * 给**界面**显示的版本（v0.2.66）。
     *
     * 与 [forModel] 的区别：剥掉 ANSI 转义码。真机上 `ls` 的输出带颜色序列，
     * 直接显示就是 `[0m[01;36mComfyUI[0m` 这种乱码。
     * 显示时也要截断——终端输出可能有几万行，全部塞进列表会卡。
     */
    fun forDisplay(maxChars: Int = 4_000): String {
        val clean = AnsiText.tidy(output)
        val body = if (clean.length <= maxChars) clean else clean.take(maxChars) + "\n…（输出过长，已截断）"
        val status = when {
            exitCode == null -> "未捕获到退出码"
            exitCode == 0 -> "成功"
            else -> "失败（退出码 $exitCode）"
        }
        // 刻意**不回显命令**：界面里紧接着上面就是"（已执行）xxx"的气泡，
        // 再写一遍 `$ xxx` 是重复（真机截图里能看到同一行出现两次）。
        // 命令本身仍留在 [command] 字段里，喂给模型的版本（forModel）照旧带上。
        return "$status\n${body.ifBlank { "（无输出）" }}"
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
            exitCode == null -> "（未捕获到退出码）"
            exitCode == 0 -> "成功（exit=0）"
            else -> "失败（exit=$exitCode）"
        }
        return "$ $command\n$status\n${body.ifBlank { "（无输出）" }}"
    }
}
