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
enum class TerminalMessageRole { USER, ASSISTANT, SYSTEM_NOTE }

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
    /** 喂回模型时的紧凑表示：太长会挤爆上下文，截断尾部（错误通常出现在尾部）。 */
    fun forModel(maxChars: Int = 2_000): String {
        val trimmed = output.trim()
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
