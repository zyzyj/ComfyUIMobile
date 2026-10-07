package com.local.comfyuimobile.mcp

/**
 * 长任务判定（v0.2.99，计划书 2.1 ④）。纯函数，可单测。
 *
 * 用途：AI 把下载/启动脚本这类长任务跑在**共用终端**上时，日志会持续刷屏、
 * 还会把后续查询命令排队卡住（终端是纯流式，没有"命令边界"）。发现这种情况
 * 就在返回里提醒分开用终端。
 *
 * 判定刻意保守——**宁可漏提，也不要对着一条 `ls` 说教**：
 * 误提会让返回变噪，而 AI 对噪话的免疫力很高（提了也不照做）。
 */
internal object TerminalTaskHeuristics {

    /** 明显耗时的命令特征。 */
    private val LONG_TASK = listOf(
        Regex("""\bpip(3)?\s+install\b"""),
        Regex("""\bwget\b"""),
        Regex("""\bcurl\b[^\n]*\s-O\b"""),
        Regex("""\bgit\s+(clone|pull|fetch)\b"""),
        Regex("""\b(apt|apt-get|yum|apk)\s+(install|update|upgrade)\b"""),
        Regex("""\b(conda|mamba)\s+(install|create|env\s+update)\b"""),
        Regex("""\b(huggingface-cli|hf)\s+download\b"""),
        Regex("""\bpython\S*\s+\S*(sync|download|setup|install)\S*\.py\b"""),
        Regex("""\b(bash|sh)\s+\S*(start|setup|install|_st)\S*\.sh\b"""),
        Regex("""\b(tar|unzip|7z)\b"""),
        Regex("""\b(pytest|python\s+-m\s+pytest)\b"""),
        Regex("""\bpython\S*\s+\S*(train|finetune)\S*\.py\b"""),
    )

    /** 已经放后台/不等待的写法——那就不必提醒分开终端了。 */
    private val BACKGROUNDED = Regex("""&\s*$|nohup\b|\bsetsid\b|\|\s*tee\b|disown\b""")

    /** 已经在用非默认终端（说明它已在分工）也无需提醒。 */
    fun longTaskHint(command: String, terminalName: String): String? {
        val cmd = command.trim()
        if (cmd.isEmpty()) return null
        // 已经在用非默认终端：说明它已经在分工，不必再说。
        if (terminalName != DEFAULT_TERMINAL) return null
        if (BACKGROUNDED.containsMatchIn(cmd)) return null
        if (!LONG_TASK.any { it.containsMatchIn(cmd) }) return null
        return "\n提示：这是长任务。建议放到**独立终端**跑（如 terminal=\"work\"），" +
            "否则它的输出会持续刷进当前终端，后续查询命令也会被排在它后面。"
    }

    /** 与 [McpTerminalHost] 保持同一个默认名。 */
    private const val DEFAULT_TERMINAL = "default"
}
