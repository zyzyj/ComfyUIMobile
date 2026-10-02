package com.local.comfyuimobile.model

/**
 * 对话上下文管理（v0.2.64）。
 *
 * 为什么需要它：以前把最近 8 条消息原样拼进 prompt 就算完事。终端助手每一轮都会带回
 * 命令输出（几百字），几轮下来 prompt 就膨胀到几千字；小上下文模型直接报
 * "context length exceeded"，而用户没有任何办法——既看不到用了多少，也不能压缩。
 *
 * 业界做法（本实现的参照）：
 *  - **Claude Code** 用 micro-compaction：旧的工具结果原地替换成
 *    `[Old tool result content cleared]`，最近的对话原样保留，不额外调模型；
 *  - **Cline** 用规则式 truncation 兜底：接近窗口上限时按比例丢弃较早的消息；
 *  - **Open Multi-Agent** 把这类做法叫 `compact` 策略——规则式、无额外 LLM 调用。
 *
 * 所以这里也**不调模型**：命令输出优先被压缩（它最占地方、时效性最强），
 * 还不够才丢弃较早的整轮。最近几轮永远原样保留——那是模型判断当前状况的依据。
 *
 * 纯 Kotlin，可单测。
 */
object AssistantContext {

    /**
     * 会话历史部分（不含 system prompt）的 token 预算。
     *
     * 取 6000 是保守值：不少便宜/本地模型只有 8k 窗口，system prompt（守则）已占约
     * 1200，留出余量才不会一上来就超。宁可早压缩，也不要发了请求才失败。
     */
    const val DEFAULT_TOKEN_BUDGET = 6_000

    /** 达到预算的这个比例时，界面提示「上下文偏长」。 */
    const val NOTICE_RATIO = 0.75

    /** 被压缩掉的命令输出在 prompt 里的占位文本。 */
    const val OMITTED_OUTPUT = "[较早的命令输出已省略]"

    /** 丢弃整轮时的提示前缀。 */
    const val OMITTED_TURNS_PREFIX = "（更早的对话已省略）"

    /**
     * 粗略估算 token 数。
     *
     * 中文约 1 字 1 token，英文约 4 字符 1 token——不需要精确，量级对就够用了，
     * 因为预算本身留了很大余量。
     */
    fun estimateTokens(text: String): Int {
        var wide = 0
        var narrow = 0
        for (ch in text) {
            if (ch.code >= 0x2E80) wide++ else narrow++
        }
        return wide + (narrow + 3) / 4
    }

    /**
     * 压缩结果。
     *
     * @param text 最终发给模型的用户消息
     * @param omittedOutputs 被占位的命令输出条数
     * @param omittedTurns 被整条丢弃的消息条数
     * @param tokens 压缩后历史部分的估算 token
     */
    data class Transcript(
        val text: String,
        val omittedOutputs: Int = 0,
        val omittedTurns: Int = 0,
        val tokens: Int = 0,
    ) {
        val compacted: Boolean get() = omittedOutputs > 0 || omittedTurns > 0
    }

    /**
     * 把对话历史 + 本次提问拼成一条 user 消息，必要时压缩。
     *
     * 顺序刻意如此：**先压缩命令输出，再丢整轮**。命令输出的信息密度最低
     * （大半是可以重新跑出来的原始文本），而且越旧越可能已经过时；
     * 对话本身则承载着"用户到底要什么"，优先保。
     */
    fun buildTranscript(
        history: List<TerminalChatMessage>,
        prompt: String,
        budgetTokens: Int = DEFAULT_TOKEN_BUDGET,
    ): Transcript {
        if (history.isEmpty()) return Transcript(text = prompt, tokens = estimateTokens(prompt))

        val lines = history.map { it.toTranscriptLine() }.toMutableList()
        var omittedOutputs = 0
        var omittedTurns = 0

        fun joined(): String = if (lines.isEmpty()) "" else lines.joinToString("\n")

        // 第一轮：从最旧的命令输出开始占位
        if (estimateTokens(joined()) > budgetTokens) {
            for (i in history.indices) {
                // 命令输出与系统旁注都是"可牺牲"的（信息密度低、越旧越可能过时），
                // 但命令输出通常长得多，先动它。
                if (history[i].role != TerminalMessageRole.TERMINAL_OUTPUT &&
                    history[i].role != TerminalMessageRole.SYSTEM_NOTE
                ) {
                    continue
                }
                lines[i] = OMITTED_OUTPUT
                omittedOutputs++
                if (estimateTokens(joined()) <= budgetTokens) break
            }
        }

        // 第二轮：仍超预算就从最旧的整条消息丢起（永远保留最后两条）
        while (estimateTokens(joined()) > budgetTokens && lines.size > 2) {
            lines.removeAt(0)
            omittedTurns++
        }
        if (omittedTurns > 0) lines.add(0, OMITTED_TURNS_PREFIX)

        val head = when {
            lines.isEmpty() -> ""
            omittedTurns > 0 -> joined() + "\n"
            else -> "Conversation so far:\n" + joined() + "\n"
        }
        val tail = "User: $prompt"
        return Transcript(
            text = head + tail,
            omittedOutputs = omittedOutputs,
            omittedTurns = omittedTurns,
            tokens = estimateTokens(head),
        )
    }

    private fun TerminalChatMessage.toTranscriptLine(): String {
        val speaker = when (role) {
            TerminalMessageRole.USER -> "User"
            TerminalMessageRole.ASSISTANT -> "You"
            TerminalMessageRole.SYSTEM_NOTE -> "System"
            TerminalMessageRole.TERMINAL_OUTPUT -> "System"
        }
        return "$speaker: ${text.take(MAX_LINE_CHARS)}"
    }

    /** 单条消息在 prompt 里的上限：防某一条超长回复独占预算。 */
    private const val MAX_LINE_CHARS = 1_200
}
