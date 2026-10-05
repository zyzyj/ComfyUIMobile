package com.local.comfyuimobile.mcp

import java.util.ArrayDeque

/**
 * MCP 调用日志（v0.2.88）。
 *
 * 排查"AI 说它调了但没反应"的唯一手段（对标 MCPDroid 的 Server Log、
 * VS Code MCP 的 /diagnostics）。
 *
 * 设计约束：
 *  - **只在内存**：进程死了日志就没——手机端没必要持久化，且终端输出可能带敏感路径；
 *  - **不记 token**：请求头里的 Authorization 一律不进日志；
 *  - 环形上限 [MAX_ENTRIES]，满了丢最旧。
 */
object McpCallLog {

    data class Entry(
        val timestamp: Long,
        val summary: String,
        val ok: Boolean,
        val detail: String = "",
    )

    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)

    @Synchronized
    fun log(summary: String, ok: Boolean, detail: String = "") {
        if (entries.size >= MAX_ENTRIES) entries.pollFirst()
        entries.addLast(Entry(System.currentTimeMillis(), summary, ok, detail))
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    /** 最近一次调用的摘要；无调用返回 null（页面显示"尚未调用"）。 */
    @Synchronized
    fun last(): Entry? = entries.lastOrNull()

    private const val MAX_ENTRIES = 50
}
