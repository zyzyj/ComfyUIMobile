package com.local.comfyuimobile.model

/**
 * 一次工具调用（命令）在界面上的**唯一单元**（v0.2.83）。
 *
 * 背景（学 OpenHands 的「观察就地替换动作」）：以前一次命令执行在对话流里是
 * **4 条独立消息 + 1 个列表外卡片**——「（已执行）xxx」标记气泡、命令输出气泡、
 * 待确认卡片、系统旁注……散在流里，用户要自己把它们拼成一个逻辑单元。
 *
 * 现在收敛成一个 [ToolCall]：状态经 [status] **原地迁移**
 * （NEEDS_CONFIRM → RUNNING → OK/FAILED/TIMEOUT），
 * 命令、耗时、输出、可视化卡片都在同一个对象上。
 *
 * 与 prompt 层的关系：给模型看的那份仍用 `终端输出:` 标记（防注入语义不变），
 * 只是它现在从本对象的 [output] 生成，而不再来自一条独立消息。
 */
data class ToolCall(
    val id: String,
    val command: String,
    val status: ToolCallStatus,
    val riskTier: RiskTier,
    /** 点击「执行」的时刻（0 = 还没开始）。用于算 [durationMs]。 */
    val startedAt: Long = 0L,
    /** 命令耗时；未跑完或未捕获为 null。卡片上必须显示（40 秒的安装会被读成"AI 在想"）。 */
    val durationMs: Long? = null,
    val exitCode: Int? = null,
    /** 终端原始输出（已剥 ANSI，界面用）。 */
    val output: String = "",
    /** 解析出的可视化卡片（nvidia-smi / df …）；解析失败为 null。 */
    val insight: InsightCard? = null,
    /** 命中用户白名单（v0.2.83）——卡片上标一句，用户知道"这条为什么没问就跑了"。 */
    val trusted: Boolean = false,
) {
    val finished: Boolean
        get() = status == ToolCallStatus.OK || status == ToolCallStatus.FAILED ||
            status == ToolCallStatus.TIMEOUT || status == ToolCallStatus.SKIPPED

    /** grep 无匹配（rc=1 且无输出）：不是错误，界面显示"无匹配"。 */
    val noMatch: Boolean
        get() = exitCode == 1 && output.isBlank() &&
            TerminalCommandSafety.isMatchSearchCommand(command)

    /**
     * 该工具调用要不要写进喂给模型的上下文（v0.2.83）。
     *
     * 只有**真正跑过且已结束**的才算（OK/FAILED/TIMEOUT）。未跑（NEEDS_CONFIRM/RUNNING）
     * 与用户跳过的（SKIPPED）不写——模型不该把没发生的事当成已发生。
     * 注意与 [finished] 的区别：这里**排除 SKIPPED**。
     * 无匹配也要写（输出为空但有结论）。
     */
    val reportableToModel: Boolean
        get() = status == ToolCallStatus.OK || status == ToolCallStatus.FAILED ||
            status == ToolCallStatus.TIMEOUT
}

/** 工具调用的生命周期状态（原地迁移）。 */
enum class ToolCallStatus {
    /** 等用户点「执行」（或「跳过」）。 */
    NEEDS_CONFIRM,
    /** 已发送，等输出。 */
    RUNNING,
    /** 退出码 0。 */
    OK,
    /** 非零退出码。 */
    FAILED,
    /** 没捕到退出码（超时）。 */
    TIMEOUT,
    /** 用户点了跳过。 */
    SKIPPED,
}

/**
 * 风险档位（v0.2.83，学 Claude Code 的三档 + Cursor 的颜色语义）。
 *
 * 与 [TerminalCommandSafety] 的布尔判定对应，但显式化后卡片能按档位配色，
 * 用户一眼看出"这条要不要仔细看"。
 */
enum class RiskTier {
    /** 只读，无副作用。 */
    READ_ONLY,
    /** 写操作（安装、下载、覆盖）。 */
    WRITE,
    /** 破坏性（删除、格式化）；且灾难性命令永远需要确认。 */
    DESTRUCTIVE,
}
