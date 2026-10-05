package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.data.ApiPromptParser
import com.local.comfyuimobile.model.ParameterField
import com.local.comfyuimobile.model.ParameterKind
import org.json.JSONObject

/**
 * 把「提示词 + 模型」注入一个 **API 格式**的工作流（v0.2.85）。
 *
 * 为什么只支持 API 格式：ComfyUI 的 **UI 格式**（`{nodes:[...]}`）转成可提交的 API 格式
 * （`{id:{class_type,inputs}}`）走的是前端 `graphToPrompt()`，那条路径在本项目里依赖
 * WebView（[com.local.comfyuimobile.bridge.ComfyBridge]）——而 MCP server 跑在前台服务里，
 * 拿不到 Activity。所以 MCP 的 `generate` 只吃 API 格式工作流（ComfyUI 菜单
 * `Workflow → Export (API)` 导出的那种），纯 Kotlin 改 JSON 即可，无需前端往返。
 *
 * 改动策略（保守优先）：
 *  - 只改**必需**的输入；找不到目标节点就明确报错，绝不"猜一个节点塞进去"——错了会在
 *    服务器侧产生一张完全不相干的图，比直接失败更难排查；
 *  - 深拷贝入参，失败可安全丢弃（与 [com.local.comfyuimobile.data.ApiPromptBuilder] 一致）。
 */
internal object McpPromptPlanner {

    /** 采样器节点类型：从它的 positive/negative 连线定位正/负向提示词节点。 */
    private val SAMPLER_TYPES = setOf("KSampler", "KSamplerAdvanced", "SamplerCustom")

    /** 提示词节点类型。 */
    private val PROMPT_TYPES = setOf("CLIPTextEncode", "BNK_CLIPTextEncodeAdvanced")

    private val CHECKPOINT_TYPES = setOf("CheckpointLoaderSimple", "CheckpointLoader", "UNETLoader")

    private val LORA_TYPES = setOf("LoraLoader", "LoraLoaderModelOnly")

    sealed interface Result {
        data class Ok(val promptJson: String, val applied: List<String>) : Result

        data class Failure(val message: String) : Result
    }

    /**
     * @param basePrompt API 格式的工作流
     * @param request 用户/模型给的参数
     */
    /**
     * 列出工作流里可调的字段（v0.2.91，`describe_workflow` 用）。
     *
     * 只返回**可注入**的字段：连线型（linked）改不动，交给模型只会让它白试。
     * 每项给出 key（注入时用）、name（节点里的字段名）、当前值、可选值或范围。
     */
    fun fields(basePrompt: JSONObject): List<ParameterField> =
        runCatching { ApiPromptParser.parse(basePrompt) }.getOrNull()?.fields.orEmpty()
            .filter { !it.linked && it.kind != ParameterKind.UNSUPPORTED }

    /**
     * 按 field key 注入任意参数（v0.2.91）。
     *
     * 与 [plan] 分开：提示词/模型那几个字段有专门的定位逻辑（要找节点、要判连线），
     * 而这里是模型**明确指名**的 key，直接写即可。混在一起两边都难改。
     *
     * 未识别的 key 必须报出来——静默忽略会让模型以为改了 steps 其实没改，
     * 出的图与预期不符还查不出原因。
     */
    fun applyParams(prompt: JSONObject, params: Map<String, String>): ParamsResult {
        if (params.isEmpty()) return ParamsResult(prompt.toString(), emptyList(), emptyList())
        val target = JSONObject(prompt.toString())
        val available = fields(target).associateBy { it.key }
        val applied = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        for ((key, raw) in params) {
            val field = available[key] ?: run {
                unknown += key
                return@run
            }
            val decoded = decodeForKind(raw, field.kind) ?: run {
                unknown += key
                return@run
            }
            val inputs = target.optJSONObject(field.nodeId)?.optJSONObject("inputs") ?: run {
                unknown += key
                return@run
            }
            inputs.put(field.name, decoded)
            applied += "$key=$raw"
        }
        return ParamsResult(target.toString(), applied, unknown)
    }

    data class ParamsResult(
        val promptJson: String,
        val applied: List<String>,
        val unknownKeys: List<String>,
    )

    /**
     * 把模型给的字符串还原成工作流要的原生类型。
     *
     * 直接塞字符串有两种无声错误：要么服务端报类型错，要么（更糟）把 seed 的 "12"
     * 当成别的。整数字段必须是数字。
     */
    private fun decodeForKind(raw: String, kind: ParameterKind): Any? = when (kind) {
        ParameterKind.INTEGER -> raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
        ParameterKind.DECIMAL -> raw.toDoubleOrNull()
        ParameterKind.BOOLEAN -> raw.toBooleanStrictOrNull()
        else -> raw
    }

    fun plan(basePrompt: JSONObject, request: GenerateRequest): Result {
        val prompt = JSONObject(basePrompt.toString())
        val parsed = runCatching { ApiPromptParser.parse(prompt) }.getOrNull()
            ?: return Result.Failure("工作流不是有效的 API 格式（无法解析节点）")

        val nodes = parsed.nodes.associateBy { it.id }
        val applied = mutableListOf<String>()

        // 1. 正/负向提示词：优先经采样器的连线定位（最可靠），退回"第一个提示词节点"。
        val sampler = parsed.nodes.firstOrNull { it.classType in SAMPLER_TYPES }
        val positiveId = sampler?.links?.get("positive")?.nodeId
            ?.takeIf { isPromptNode(nodes, it) }
            ?: parsed.executionChain.firstOrNull { isPromptNode(nodes, it) }

        if (positiveId == null) {
            return Result.Failure(
                "工作流里找不到可写正向提示词的节点（需要 CLIPTextEncode）；" +
                    "请确认导出的是 API 格式且包含提示词节点",
            )
        }
        if (!writeText(prompt, positiveId, "text", request.prompt)) {
            return Result.Failure("无法写入正向提示词：节点 $positiveId 没有 text 输入")
        }
        applied += "prompt"

        // 负向：优先采样器的 negative 连线；没有就跳过（不是所有工作流都有负向）。
        if (request.negative.isNotBlank()) {
            val negativeId = sampler?.links?.get("negative")?.nodeId
                ?.takeIf { isPromptNode(nodes, it) }
            if (negativeId != null && writeText(prompt, negativeId, "text", request.negative)) {
                applied += "negative"
            }
        }

        // 2. 模型：只在显式指定时改，避免把用户精心挑的模型换掉。
        request.checkpoint?.let { name ->
            val id = parsed.nodes.firstOrNull { it.classType in CHECKPOINT_TYPES }?.id
                ?: return Result.Failure("工作流里没有模型加载节点，无法指定 checkpoint")
            if (!writeText(prompt, id, "ckpt_name", name)) {
                return Result.Failure("无法写入模型名：节点 $id 没有 ckpt_name 输入")
            }
            applied += "checkpoint"
        }

        // 3. LoRA：只在显式指定时改；没有 LoRA 节点就明确失败（而不是静默忽略，
        //    否则用户会以为 LoRA 生效了、拿到一张没加 LoRA 的图）。
        request.lora?.let { name ->
            val id = parsed.nodes.firstOrNull { it.classType in LORA_TYPES }?.id
                ?: return Result.Failure("工作流里没有 LoRA 加载节点，无法指定 lora")
            if (!writeText(prompt, id, "lora_name", name)) {
                return Result.Failure("无法写入 LoRA 名：节点 $id 没有 lora_name 输入")
            }
            applied += "lora"
        }

        if (request.count > 1) {
            com.local.comfyuimobile.bridge.PromptBatch.inject(prompt, request.count)
            applied += "batch_size"
        }

        return Result.Ok(prompt.toString(), applied)
    }

    private fun isPromptNode(nodes: Map<String, ApiPromptParser.ApiNode>, id: String): Boolean =
        nodes[id]?.classType?.let { it in PROMPT_TYPES } == true

    private fun writeText(prompt: JSONObject, nodeId: String, key: String, value: String): Boolean {
        val inputs = prompt.optJSONObject(nodeId)?.optJSONObject("inputs") ?: return false
        if (!inputs.has(key)) return false
        inputs.put(key, value)
        return true
    }
}
