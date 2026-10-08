package com.local.comfyuimobile.data

import org.json.JSONObject

/**
 * 工作流 JSON 格式探测。
 *
 * ComfyUI 有两种常见格式：
 *  1. 画布格式：{ nodes:[{id,type,...}], links:[...] }（从 App/网页导出）
 *  2. API prompt 格式：{ "3": {"class_type":"KSampler","inputs":{...}}, ... }
 *     （ComfyUI 前端 "Save (API Format)" 导出，或第三方工具生成的）
 *
 * 导入时必须识别出 API 格式，否则会被当成"不是工作流"拒绝。
 */
object WorkflowFormat {
    fun isCanvas(root: JSONObject): Boolean = root.optJSONArray("nodes") != null

    /**
     * 给界面的短标记（v0.2.99）。
     *
     * @param json 已缓存的工作流正文；null 表示**不知道**（没缓存过）。
     * @return `"API"` / `"画布"` / null（未知就不标，不要猜）。
     *
     * 为什么做成"传已缓存内容"而不是"自己读文件"：界面要标记几十个工作流的格式，
     * 逐个读就是几十次请求（AI Studio 反代下还要考虑登录态与限流）。
     * 只标记本机已有内容的那些，零请求；用户打开过的工作流自然会亮出标记。
     */
    fun labelOf(json: String?): String? {
        val raw = json?.takeIf { it.isNotBlank() } ?: return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return when {
            isApiPrompt(root) -> "API"
            isCanvas(root) -> "画布"
            else -> null
        }
    }

    /**
     * 顶层是 {节点id: {class_type, inputs, ...}} 的 API prompt 格式判定：
     * 至少一个节点，且每个条目的 class_type 都是非空字符串。
     */
    fun isApiPrompt(root: JSONObject): Boolean {
        if (root.optJSONArray("nodes") != null) return false
        val keys = root.keys()
        if (!keys.hasNext()) return false
        var matched = 0
        var total = 0
        while (keys.hasNext()) {
            val entry = root.optJSONObject(keys.next()) ?: return false
            total++
            if (entry.optString("class_type").isNotBlank()) matched++
        }
        return total > 0 && matched == total
    }
}
