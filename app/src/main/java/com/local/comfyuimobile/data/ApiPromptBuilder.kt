package com.local.comfyuimobile.data

import com.local.comfyuimobile.bridge.PromptBatch
import com.local.comfyuimobile.model.ParameterField
import org.json.JSONObject

/**
 * 把用户在参数页的改动写回 API prompt。
 *
 * 与画布路径的区别：画布路径要把改动同步进 WebView 前端、再调用 graphToPrompt
 * 导出；API 格式本来就是最终执行结构，直接改 JSON 即可，省掉整条前端往返。
 *
 * 设计要点：
 *  - 深拷贝入参，绝不修改调用方持有的原始 prompt（失败可安全丢弃）；
 *  - 只写回真正改过的字段，未改动的保持原样；
 *  - 值按 valueJson 反序列化，数字仍是数字、字符串仍是字符串；
 *  - batch_size 复用现成的 PromptBatch，保证与画布路径行为一致。
 *
 * 纯 Kotlin 实现，可脱离 Android SDK 单测。
 */
object ApiPromptBuilder {

    /** ApiPromptParser 生成的 field key 分隔符。 */
    const val KEY_SEPARATOR = "::"

    data class BuildResult(
        val promptJson: String,
        /** 实际写回的字段数，便于日志与提示。 */
        val appliedCount: Int,
        /** batch_size 注入是否命中节点，false 表示工作流不支持批量。 */
        val batchApplied: Boolean,
    )

    fun applyFields(
        basePrompt: JSONObject,
        fields: List<ParameterField>,
        batchSize: Int = 1,
    ): BuildResult {
        val prompt = JSONObject(basePrompt.toString())
        var applied = 0
        for (field in fields) {
            if (field.valueJson == field.originalValueJson) continue
            val node = prompt.optJSONObject(field.nodeId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue
            val decoded = decodeValue(field.valueJson) ?: continue
            inputs.put(field.name, decoded)
            applied++
        }
        val batchApplied = if (batchSize > 1) {
            PromptBatch.inject(prompt, batchSize)
        } else {
            true
        }
        return BuildResult(prompt.toString(), applied, batchApplied)
    }

    /**
     * 只替换指定 LoRA 槽位的名称与强度——LoRA 对比功能的核心改动入口。
     * 槽位不存在时静默跳过，由调用方决定如何提示。
     */
    fun applyLora(
        basePrompt: JSONObject,
        updates: Map<String, LoraUpdate>,
    ): JSONObject {
        val prompt = JSONObject(basePrompt.toString())
        for ((nodeId, update) in updates) {
            val inputs = prompt.optJSONObject(nodeId)?.optJSONObject("inputs") ?: continue
            update.loraName?.let { inputs.put("lora_name", it) }
            update.strengthModel?.let { inputs.put("strength_model", it) }
            if (inputs.has("strength_clip")) {
                update.strengthClip?.let { inputs.put("strength_clip", it) }
            }
        }
        return prompt
    }

    data class LoraUpdate(
        val loraName: String? = null,
        val strengthModel: Double? = null,
        val strengthClip: Double? = null,
    )

    /**
     * valueJson -> 原生值。非法 JSON 返回 null（调用方跳过，不写坏 prompt）。
     * 用 {"v": ...} 包裹是为了连纯标量（true / 123 / "x"）也能解析。
     */
    private fun decodeValue(json: String): Any? = runCatching {
        val holder = JSONObject("{\"v\":$json}")
        if (!holder.has("v")) null else holder.opt("v")
    }.getOrNull()
}
