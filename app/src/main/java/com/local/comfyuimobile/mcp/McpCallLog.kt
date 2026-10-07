package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.data.AppLogger
import java.util.ArrayDeque

/**
 * MCP 调用日志（v0.2.88；v0.2.97 增加落盘）。
 *
 * 排查"AI 说它调了但没反应"的唯一手段（对标 MCPDroid 的 Server Log、
 * VS Code MCP 的 /diagnostics）。
 *
 * 设计约束：
 *  - 内存环形上限 [MAX_ENTRIES]，满了丢最旧（页面展示用）；
 *  - **同时镜像到 [AppLogger] 落盘**（v0.2.97）：原先只存内存，进程一死就无迹可查，
 *    而完全控制终端后必须能追溯"AI 到底跑过什么命令"（清单 §八）。这里不另建
 *    日志文件，直接复用 AppLogger 已有的落盘与轮转。
 *  - **不记 token / 不记完整命令体**：只记摘要，避免把凭据或大段输出写进文件。
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
        // 落盘供事后追溯。摘要里不含 token；detail 由调用方保证不长（多为耗时/错误原因）。
        runCatching {
            AppLogger.info("MCP 调用 ${if (ok) "成功" else "失败"}：$summary" +
                detail.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty())
        }
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
