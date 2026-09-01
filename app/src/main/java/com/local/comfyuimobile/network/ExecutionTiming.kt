package com.local.comfyuimobile.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * 从 history 的 `status.messages` 里算任务耗时。
 *
 * ComfyUI 往 messages 里塞的事件大致三种：
 *  - `execution_start`：开始执行（可能不止一条，重跑/续跑时会出现多条）；
 *  - `execution_success` / `execution_error` / `execution_interrupted`：**终态**，
 *    任务走到这里就结束了；
 *  - `execution_cached`：有节点命中缓存。它**不是终态**——部分节点缓存时它出现在
 *    execution_start 之后、execution_success 之前，间隔可能只有几十毫秒。
 *
 * 以前这两处各写各的，规则还相反：
 *  - `ComfyClient.executionDuration` 完全不认 execution_cached，于是"整条链路全命中
 *    缓存"的任务（没有 execution_success）耗时永远是空白；
 *  - `ResultParser.executionEnd` 把 execution_cached 当终态，于是"部分节点缓存"的
 *    任务耗时被砍成刚进缓存那一瞬间——真实跑了 8 秒，显示 0.1 秒。这正是
 *    v0.1.76 之后"耗时不准"残留的那一半原因。
 *
 * 现在统一成一条规则：**终态优先，execution_cached 兜底**——
 * 有终态就用终态（部分缓存不受影响），一条终态都没有才说明整条链路全是缓存命中，
 * 这时用 execution_cached 收尾。
 *
 * 纯 Kotlin，可单测。
 */
object ExecutionTiming {

    /** 真正的终态事件。 */
    private val TERMINAL = setOf("execution_success", "execution_error", "execution_interrupted")

    /** 缓存命中事件：可能先于终态出现，不能当终态用。 */
    private const val CACHED = "execution_cached"

    /** 开始事件。 */
    private const val START = "execution_start"

    /**
     * 开始时间戳：取**最早**一条 execution_start；一条都没有（例如整条链路全缓存、
     * 服务端没发 start）时用最早的一条消息兜底，避免耗时整个消失。
     * 取不到返回 0。
     */
    fun start(status: JSONObject?): Long = start(status?.optJSONArray("messages"))

    fun start(messages: JSONArray?): Long {
        var start = 0L
        var earliest = 0L
        scan(messages) { type, timestamp ->
            if (earliest == 0L || timestamp < earliest) earliest = timestamp
            if (type == START && (start == 0L || timestamp < start)) start = timestamp
        }
        return start.takeIf { it > 0 } ?: earliest
    }

    /**
     * 结束时间戳：终态优先；全是缓存命中（没有终态）时用 execution_cached；
     * 连缓存事件都没有时退回最晚的一条消息。取不到返回 0。
     */
    fun end(status: JSONObject?): Long = end(status?.optJSONArray("messages"))

    fun end(messages: JSONArray?): Long {
        var terminal = 0L
        var cached = 0L
        var latest = 0L
        scan(messages) { type, timestamp ->
            if (timestamp > latest) latest = timestamp
            if (type in TERMINAL && timestamp > terminal) terminal = timestamp
            if (type == CACHED && timestamp > cached) cached = timestamp
        }
        return when {
            terminal > 0 -> terminal
            cached > 0 -> cached
            else -> latest
        }
    }

    /** 执行耗时（毫秒）；时间戳取不齐返回 null。 */
    fun durationMs(status: JSONObject?): Long? {
        val messages = status?.optJSONArray("messages") ?: return null
        val start = start(messages)
        val end = end(messages)
        if (start <= 0 || end <= 0 || end < start) return null
        // 起止落在同一毫秒（比如只有一条缓存事件）时返回 null，免得界面显示"0 毫秒"。
        return (end - start).takeIf { it > 0 }
    }

    private inline fun scan(messages: JSONArray?, visit: (type: String, timestamp: Long) -> Unit) {
        if (messages == null) return
        repeat(messages.length()) { index ->
            val message = messages.optJSONArray(index) ?: return@repeat
            val type = message.optString(0)
            val timestamp = message.optJSONObject(1)?.optLong("timestamp", -1L) ?: -1L
            if (timestamp > 0) visit(type, timestamp)
        }
    }
}
