package com.local.comfyuimobile.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * 从 history 的 `status.messages` 里取任务失败的真实原因。
 *
 * 背景：任务列表以前对失败任务只显示状态字符串 `"error"`（`status_str`），
 * 用户根本看不出为什么失败——是显存不够、模型没装、还是节点参数错。
 * ComfyUI 其实把原因写在 messages 里：
 *
 * ```
 * ["execution_error", {
 *    "node_id": "3", "node_type": "KSampler",
 *    "exception_type": "RuntimeError",
 *    "exception_message": "CUDA out of memory. ..."
 * }]
 * ```
 *
 * 这里把它抽成一行能读的中文（尽量带上节点信息），纯 Kotlin、可单测。
 */
object ExecutionError {

    /** 终态事件类型。 */
    private const val ERROR = "execution_error"
    private const val INTERRUPTED = "execution_interrupted"

    /**
     * 取失败原因；没有失败事件时返回空串。
     *
     * @param nodeTitles 节点 id → 显示名（来自工作流的节点标题），用于把
     *   `node_id` 翻译成用户认得的名字；没有就退回 `节点 <id>`。
     */
    fun describe(status: JSONObject?, nodeTitles: Map<String, String> = emptyMap()): String {
        val messages = status?.optJSONArray("messages") ?: return ""
        var result = ""
        repeat(messages.length()) { index ->
            val message = messages.optJSONArray(index) ?: return@repeat
            val type = message.optString(0)
            if (type != ERROR && type != INTERRUPTED) return@repeat
            val data = message.optJSONObject(1) ?: return@repeat
            // 只取最后一条失败事件：重跑场景下最新的那条才代表本次结果。
            result = when (type) {
                INTERRUPTED -> "任务被中断"
                else -> {
                    val nodeId = data.optString("node_id")
                    val nodeType = data.optString("node_type")
                    val detail = data.optString("exception_message")
                        .ifBlank { data.optString("exception_type") }
                        .ifBlank { "服务器执行失败" }
                    val where = nodeLabel(nodeId, nodeType, nodeTitles)
                    if (where.isBlank()) detail else "$where：$detail"
                }
            }
        }
        return result
    }

    private fun nodeLabel(nodeId: String, nodeType: String, titles: Map<String, String>): String {
        if (nodeId.isBlank()) return ""
        val title = titles[nodeId]?.takeIf { it.isNotBlank() }
        val name = title ?: nodeType.ifBlank { "节点 $nodeId" }
        return name
    }
}