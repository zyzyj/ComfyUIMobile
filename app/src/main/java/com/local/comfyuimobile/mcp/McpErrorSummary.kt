package com.local.comfyuimobile.mcp

/**
 * ComfyUI 执行错误的摘要化（v0.2.97）。
 *
 * 为什么需要：`/history` 里的 `exception_message` 是**Python 完整 traceback**，
 * 动辄几千字符。原样返回给模型既费 token，又把真正有用的那行淹没在调用栈里。
 *
 * 保留策略（按重要性）：
 *  1. **最后一行**——Python 的异常类型 + 消息（`RuntimeError: CUDA out of memory`）
 *  2. **末尾的非栈帧行**——常带上下文（如 OOM 时的显存数字）
 *  3. 前面如果只剩栈帧，用一行省略号代替
 *
 * 纯函数，可单测。
 */
internal object McpErrorSummary {

    /** 单条错误的字符上限。留够上下文，又不至于把模型上下文挤爆。 */
    const val MAX_CHARS = 400

    /** 末尾保留的行数（异常行通常在最后）。 */
    private const val TAIL_LINES = 3

    /** 看起来像栈帧的行：`  File "x.py", line 12, in foo` 或 `    ^^^^^`。 */
    private val FRAME_LINE = Regex("""^\s*(File\s+"|at\s|\^+$|\||~~~)""")

    fun summarize(raw: String, maxChars: Int = MAX_CHARS): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        if (text.length <= maxChars) return text

        val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        // 末尾几行是信息量最高的部分（异常类型与消息）。
        val tail = lines.takeLast(TAIL_LINES).filterNot { FRAME_LINE.matches(it) }
        val head = tail.ifEmpty { lines.takeLast(1) }.joinToString("\n")
        val prefix = if (lines.size > tail.size) "…（前面 ${lines.size - tail.size} 行调用栈已省略）\n" else ""
        val body = prefix + head
        return if (body.length <= maxChars) body else body.take(maxChars) + "…（已截断）"
    }
}
