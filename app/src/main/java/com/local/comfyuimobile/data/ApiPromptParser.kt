package com.local.comfyuimobile.data

import com.local.comfyuimobile.bridge.ParameterClassifier
import com.local.comfyuimobile.model.ParameterField
import com.local.comfyuimobile.model.ParameterSection
import org.json.JSONObject

/**
 * API prompt 格式工作流的原生解析。
 *
 * 背景：ComfyUI "Save (API Format)" 导出的是 {节点id: {class_type, inputs}}，
 * 画布前端（app.loadGraphData）并不直接认这种结构。之前 App 依赖前端的
 * /scripts/convertPromptToGraph.js 做转换，模块不存在时异常被静默吞掉，
 * 结果画布为空并报「当前工作流没有已连线的输出节点」。
 *
 * 这里改为在 Kotlin 侧直接解析 API prompt：
 *  - 不需要 WebView / 前端加载，也不受 ComfyUI 版本影响；
 *  - 生成的 [ParameterField] 与画布路径完全一致，UI 无需区分；
 *  - 顺带识别 LoRA 槽位，供对比功能复用。
 *
 * 纯 Kotlin 实现，可脱离 Android SDK 单测。
 */
object ApiPromptParser {

    /** 视为"输出节点"的类型——执行链的终点。 */
    private val OUTPUT_TYPES = setOf(
        "SaveImage", "PreviewImage", "SaveAnimatedWEBP", "SaveAnimatedPNG",
        "SaveVideo", "VHS_VideoCombine", "SaveImageWithMetadata", "SaveAudio",
    )

    /** LoRA 加载节点，其 lora_name / strength_* 是可对比的槽位。 */
    val LORA_TYPES = setOf("LoraLoader", "LoraLoaderModelOnly")

    /** object_info 里代表"基础类型"的取值，其余（MODEL/CLIP/LATENT…）一律当连线输入。 */
    private val SCALAR_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN", "NUMBER", "DOUBLE", "LONG")

    data class LinkRef(val nodeId: String, val slot: Int)

    data class ApiNode(
        val id: String,
        val classType: String,
        val title: String,
        /** 非连线的字面量输入（widget 值）。 */
        val widgetValues: Map<String, Any?>,
        /** 连线输入：输入名 -> 上游节点。 */
        val links: Map<String, LinkRef>,
    )

    data class LoraSlot(
        val nodeId: String,
        val loraName: String,
        val strengthModel: Double?,
        val strengthClip: Double?,
        /** 该槽位上游的 model 来源节点 id，null 表示直接来自加载器。 */
        val modelSource: String?,
        val clipSource: String?,
    )

    data class ParseResult(
        val nodes: List<ApiNode>,
        /** 输出节点及其全部上游，按拓扑序排列。 */
        val executionChain: List<String>,
        val outputNodeIds: List<String>,
        val loraSlots: List<LoraSlot>,
        val fields: List<ParameterField>,
    )

    fun parse(prompt: JSONObject, objectInfo: JSONObject? = null): ParseResult {
        val nodes = readNodes(prompt)
        val byId = nodes.associateBy { it.id }
        val outputIds = nodes.filter { it.classType in OUTPUT_TYPES }.map { it.id }
        val chain = collectAncestors(outputIds, byId)
        val ordered = topoOrder(chain, byId)
        val fields = buildFields(ordered, byId, objectInfo)
        return ParseResult(
            nodes = nodes,
            executionChain = ordered,
            outputNodeIds = outputIds,
            loraSlots = readLoraSlots(byId),
            fields = fields,
        )
    }

    private fun readNodes(prompt: JSONObject): List<ApiNode> {
        val result = mutableListOf<ApiNode>()
        val keys = prompt.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val node = prompt.optJSONObject(id) ?: continue
            val classType = node.optString("class_type")
            if (classType.isBlank()) continue
            val title = node.optJSONObject("_meta")?.optString("title").orEmpty()
                .ifBlank { classType }
            val inputs = node.optJSONObject("inputs") ?: JSONObject()
            val links = mutableMapOf<String, LinkRef>()
            val widgets = linkedMapOf<String, Any?>()
            val names = inputs.keys()
            while (names.hasNext()) {
                val name = names.next()
                val value = inputs.opt(name)
                val ref = asLink(value)
                if (ref != null) links[name] = ref else widgets[name] = unwrap(value)
            }
            result += ApiNode(id, classType, title, widgets, links)
        }
        // 节点顺序：数字 id 按数值排，非数字按字典序，保证输出稳定可测。
        return result.sortedWith(compareBy({ it.id.toLongOrNull() ?: Long.MAX_VALUE }, { it.id }))
    }

    /** ComfyUI 用 ["节点id", 输出槽位] 表示连线。 */
    private fun asLink(value: Any?): LinkRef? {
        val array = value as? org.json.JSONArray ?: return null
        if (array.length() != 2) return null
        val nodeId = array.optString(0)
        if (nodeId.isBlank()) return null
        val slot = array.optInt(1, -1)
        return if (slot >= 0) LinkRef(nodeId, slot) else null
    }

    private fun unwrap(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is org.json.JSONArray -> value
        else -> value
    }

    /** 从输出节点顺着依赖往上游收集（谁被我引用 = 我的上游）。 */
    private fun collectAncestors(startIds: List<String>, byId: Map<String, ApiNode>): Set<String> {
        val seen = linkedSetOf<String>()
        fun walk(id: String) {
            if (id in seen) return
            val node = byId[id] ?: return
            seen += id
            node.links.values.forEach { walk(it.nodeId) }
        }
        startIds.forEach(::walk)
        return seen
    }

    /** 拓扑排序：上游在前，下游在后；同层按 id 排序，输出稳定。 */
    private fun topoOrder(chain: Set<String>, byId: Map<String, ApiNode>): List<String> {
        val result = mutableListOf<String>()
        val visiting = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in result || id in visiting) return
            visiting += id
            byId[id]?.links?.values.orEmpty()
                .map { it.nodeId }
                .filter { it in chain }
                .sortedWith(compareBy({ it.toLongOrNull() ?: Long.MAX_VALUE }, { it }))
                .forEach(::visit)
            visiting -= id
            if (id !in result) result += id
        }
        chain.toList()
            .sortedWith(compareBy({ it.toLongOrNull() ?: Long.MAX_VALUE }, { it }))
            .forEach(::visit)
        return result
    }

    private fun readLoraSlots(byId: Map<String, ApiNode>): List<LoraSlot> {
        val slots = mutableListOf<LoraSlot>()
        for ((id, node) in byId) {
            if (node.classType !in LORA_TYPES) continue
            slots += LoraSlot(
                nodeId = id,
                loraName = node.widgetValues["lora_name"]?.toString().orEmpty(),
                strengthModel = asDouble(node.widgetValues["strength_model"]),
                strengthClip = asDouble(node.widgetValues["strength_clip"]),
                modelSource = node.links["model"]?.nodeId,
                clipSource = node.links["clip"]?.nodeId,
            )
        }
        return slots.sortedWith(compareBy({ it.nodeId.toLongOrNull() ?: Long.MAX_VALUE }, { it.nodeId }))
    }

    private fun asDouble(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    private fun buildFields(
        chain: List<String>,
        byId: Map<String, ApiNode>,
        objectInfo: JSONObject?,
    ): List<ParameterField> {
        val fields = mutableListOf<ParameterField>()
        chain.forEachIndexed { nodeOrder, id ->
            val node = byId[id] ?: return@forEachIndexed
            val spec = objectInfo?.optJSONObject(node.classType)
            val order = widgetOrder(node, spec)
            node.widgetValues.entries
                .sortedBy { order[it.key] ?: Int.MAX_VALUE }
                .forEachIndexed { index, entry ->
                    val field = toField(node, entry, spec, nodeOrder, index)
                    if (field != null) fields += field
                }
        }
        return fields
    }

    /**
     * widget 的展示顺序：按 object_info 的 required 顺序；跳过连线输入。
     * 缺失定义时退化为输入出现顺序，保证稳定。
     */
    private fun widgetOrder(node: ApiNode, spec: JSONObject?): Map<String, Int> {
        val order = linkedMapOf<String, Int>()
        var index = 0
        listOf("required", "optional").forEach { section ->
            val group = spec?.optJSONObject("input")?.optJSONObject(section) ?: return@forEach
            val names = group.keys()
            while (names.hasNext()) {
                val name = names.next()
                if (name in order) continue
                if (name !in node.widgetValues) continue
                order[name] = index++
            }
        }
        node.widgetValues.keys.forEach { if (it !in order) order[it] = index++ }
        return order
    }

    private fun toField(
        node: ApiNode,
        entry: Map.Entry<String, Any?>,
        spec: JSONObject?,
        nodeOrder: Int,
        index: Int,
    ): ParameterField? {
        val name = entry.key
        val value = entry.value
        val (typeName, options, minimum, maximum, step) = specOf(name, spec)
        val kind = ParameterClassifier.kind(
            nodeType = node.classType,
            name = name,
            widgetType = typeName,
            value = value,
            options = options,
            dataType = typeName,
            minimum = minimum,
            maximum = maximum,
            step = step,
        )
        if (kind == com.local.comfyuimobile.model.ParameterKind.UNSUPPORTED) return null
        val section = ParameterClassifier.section(node.classType, name, kind)
        val label = ParameterClassifier.label(node.title, name, name)
        val display = when {
            value == null -> ""
            value is org.json.JSONArray -> value.toString()
            else -> value.toString()
        }
        return ParameterField(
            key = "${node.id}::$name",
            nodeId = node.id,
            nodeTitle = node.title,
            nodeType = node.classType,
            name = name,
            label = label,
            widgetType = typeName,
            kind = kind,
            valueJson = toJsonValue(value),
            displayValue = display,
            options = options,
            minimum = minimum,
            maximum = maximum,
            step = step,
            section = section,
            order = index,
            nodeOrder = nodeOrder,
            widgetIndex = index,
        )
    }

    /** 从 object_info 的 input.required/optional 里取类型定义。 */
    private fun specOf(name: String, spec: JSONObject?): Spec {
        val input = spec?.optJSONObject("input") ?: return Spec()
        listOf("required", "optional").forEach { section ->
            val entry = input.optJSONObject(section)?.optJSONArray(name) ?: return@forEach
            val type = entry.opt(0)
            val extra = entry.optJSONObject(1)
            val options = mutableListOf<String>()
            if (type is org.json.JSONArray) {
                repeat(type.length()) { i -> options += type.optString(i) }
            }
            return Spec(
                typeName = if (type is org.json.JSONArray) "COMBO" else type?.toString().orEmpty(),
                options = options,
                minimum = extra?.optNullableDouble("min"),
                maximum = extra?.optNullableDouble("max"),
                step = extra?.optNullableDouble("step"),
            )
        }
        return Spec()
    }

    private fun JSONObject.optNullableDouble(name: String): Double? =
        takeIf { it.has(name) }?.optDouble(name)?.takeIf { it.isFinite() }

    private fun toJsonValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> value.toString()
        is org.json.JSONArray -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private data class Spec(
        val typeName: String = "",
        val options: List<String> = emptyList(),
        val minimum: Double? = null,
        val maximum: Double? = null,
        val step: Double? = null,
    )
}
