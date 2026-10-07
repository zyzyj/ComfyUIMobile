package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.model.TerminalCommandSafety

/**
 * 高价值目录的删除拦截（v0.2.98，清单 §4.6）。
 *
 * ## 为什么需要，而不是直接加进 [TerminalCommandSafety.isCatastrophic]
 *
 * 那个熔断器挡的是"**整个实例没了**"（删根/家目录、格式化、关机）。而 `rm -rf ~/ComfyUI/models`
 * 并不属于那类——它的注释里写得明白：`rm -rf ~/models/loras` 有明确目标，不算灾难。
 * 这是**有意的设计**（用户自己清理时不该被拦），界面侧的三档确认机制也建在它上面。
 *
 * 但真机实测暴露了另一件事：AI 完全控制终端后，`rm -rf ~/ComfyUI/models/` 是**能执行**的，
 * 而那些模型要重新下载几十 GB——这比"某次操作失败"疼得多，且不可逆。
 *
 * 所以在 **MCP 这一侧**加一道更严的闸：只拦"删除模型 / 工作流这类高价值目录"，
 * 不动公共判定（避免波及界面侧已有行为与测试）。被拦时给可逆做法（隔离删除）。
 *
 * 刻意**不自动改写** `rm` 为 `mv`——那会让 AI 以为删掉了、实际只是移走，
 * 语义变了（本项目的第五类 bug：生成物交给外部系统后语义变化）。
 */
internal object ValuableDataGuard {

    /** 高价值目录的特征名（小写、匹配路径段）。 */
    private val VALUABLE_SEGMENTS = listOf(
        "models", "loras", "lora", "checkpoints", "vae", "embeddings",
        "controlnet", "unet", "clip", "clip_vision", "upscale_models",
        "workflows", "工作流", "模型",
    )

    /** 递归删除的开关。 */
    private val RECURSIVE = Regex("""^-[a-zA-Z]*r[a-zA-Z]*$|^--recursive$""")

    /**
     * 命令是否在递归删除高价值目录。
     *
     * 判定思路：只看**同时满足**三条的段——① 是删除类命令（rm/rmdir/shred/find -delete）
     * ② 带递归语义 ③ 目标路径里出现了高价值目录名。任一不满足就放行，
     * 宁可漏拦也不要误伤普通清理（误拦会让 AI 白折腾）。
     */
    fun isValuableDeletion(command: String): Boolean {
        // 复合命令要逐段看（`ls && rm -rf ~/ComfyUI/models` 这种）。
        val segments = TerminalCommandSafety.segmentsOf(TerminalCommandSafety.normalizeForMatch(command))
        val toScan = if (segments.size > 1) segments else listOf(command)
        return toScan.any { segment -> segmentIsValuableDeletion(segment) }
    }

    private fun segmentIsValuableDeletion(segment: String): Boolean {
        val tokens = segment.split(' ', '\t')
            .map { it.trim('"', '\'') }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false

        val deletes = tokens.any { it == "rm" || it == "rmdir" || it == "shred" }
        val findDelete = tokens.any { it == "find" } && tokens.any { it == "-delete" || it == "-exec" }
        if (!deletes && !findDelete) return false

        // rmdir / find -delete 本身就是递归语义。
        val recursive = tokens.any { RECURSIVE.matches(it) } ||
            tokens.any { it == "rmdir" || it == "-delete" || it == "-exec" }
        if (!recursive) return false

        return tokens.any { token ->
            VALUABLE_SEGMENTS.any { seg -> tokenMatchesSegment(token, seg) }
        }
    }

    /**
     * 路径里某一段等于高价值名（或其结尾就是它，如 `~/ComfyUI/models/`）。
     *
     * 用**路径段相等**而不是 `contains`：`~/models_backup_old/` 不该被误拦，
     * 而 `~/ComfyUI/models` 该拦。这是"宁可精确、不要模糊"的一贯做法。
     */
    private fun tokenMatchesSegment(token: String, segment: String): Boolean {
        if (token.startsWith("-")) return false // 选项不是路径
        val parts = token.trimEnd('/').split('/').filter { it.isNotBlank() }
        return parts.any { it.equals(segment, ignoreCase = true) }
    }
}
