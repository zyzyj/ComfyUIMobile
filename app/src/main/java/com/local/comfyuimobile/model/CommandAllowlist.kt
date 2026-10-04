package com.local.comfyuimobile.model

/**
 * 用户级命令白名单（v0.2.83）。
 *
 * 学 Cursor 的 Allowlist：让重度用户**预批准具体命令**（`git *`、`ls *`），
 * 而不是只对风险等级做粗粒度授权。命中即直接执行、零提示——这才是真正省掉点击。
 *
 * **安全边界（最重要的一条）**：[matches] 只回答"这条命令是否命中用户预批准的模式"，
 * 调用方必须**先判 [TerminalCommandSafety.isCatastrophic]**、灾难性命令永不放行。
 * 白名单是"信任声明"，不是"绕过熔断器的开关"——Claude Code 的官方立场同样如此：
 * 即使开启跳过所有权限，关键路径的 rm 也永远不自动放行。
 *
 * 纯函数、可单测：匹配规则一旦写松（如把 glob 当正则用）会静默放行危险命令，
 * 属于安全边界，不该散在 UI 或 ViewModel 里。
 */
object CommandAllowlist {

    /** 出厂预置的信任模式：都是只读、无副作用的侦察命令。 */
    val DEFAULT_PATTERNS = listOf(
        "nvidia-smi",
        "df -h*",
        "free -h",
        "ls *",
        "ls",
        "ps aux*",
        "cat *",
    )

    /**
     * 命令是否命中白名单。
     *
     * @param patterns 用户配置的模式（glob：`*` 匹配任意字符，`?` 匹配一个字符）
     *
     * 匹配是**整条命令**匹配（不是"包含"）：`ls *` 命中 `ls -la`，但不命中
     * `ls -la && rm -rf /`——后者是复合命令，必须走正常确认流程。
     */
    fun matches(command: String, patterns: List<String>): Boolean {
        val normalized = command.trim()
        if (normalized.isBlank()) return false
        return patterns.any { pattern ->
            val p = pattern.trim()
            p.isNotBlank() && globToRegex(p).matches(normalized)
        }
    }

    /**
     * glob → 正则。**不做 `**` 递归通配**——命令是单行，`*` 匹配任意字符已足够，
     * 且少的语法就少的出错面。正则元字符一律转义，避免用户写 `[` 之类把规则写歪。
     */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        glob.forEach { ch ->
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        return Regex(sb.toString())
    }
}