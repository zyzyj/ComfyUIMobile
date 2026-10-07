package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.data.ApiPromptParser
import com.local.comfyuimobile.network.NodeAvailability
import org.json.JSONObject

/**
 * 工作流提交前预检（v0.2.96）。
 *
 * 存在的理由很实在：**一次错误提交要等几十秒才在服务器侧暴露**，而在 AI Studio
 * 上那段时间是**真金白银的算力卡**。把这些错误挪到提交前，用零成本换掉它。
 *
 * 校验项都是**AI 手写/修改工作流时最常犯**的：
 *  1. 格式不是 API 格式（画布格式提交上去必失败）
 *  2. 用了服务器没装的节点类型（换机器、换镜像后常见）
 *  3. 连线指向不存在的上游节点（删节点忘了改引用——手改 JSON 的头号错误）
 *  4. 没有任何输出节点（跑完不出图，白烧算力）
 *
 * 纯函数：不碰网络（catalog 由调用方传入），可脱离 Android 单测。
 */
internal object McpWorkflowValidator {

    data class Report(
        val ok: Boolean,
        val errors: List<String>,
        val warnings: List<String>,
    ) {
        /** 给模型看的文本。有问题时明确说"别提交"，避免它抱着侥幸心理试。 */
        fun render(): String = buildString {
            if (ok) {
                append("预检通过")
                if (warnings.isNotEmpty()) {
                    append("（有 ${warnings.size} 条提醒）")
                }
                append("。可以提交。")
            } else {
                append("预检未通过，**请先修复再提交**（现在提交会失败或白跑）")
            }
            errors.forEach { append("\n✗ ").append(it) }
            warnings.forEach { append("\n⚠ ").append(it) }
        }
    }

    /**
     * @param prompt 已确认是 API 格式的工作流
     * @param catalog 服务器已注册的节点类型（[NodeAvailability.parseCatalog] 的结果）；
     *        null 表示这次没查到——此时跳过节点存在性校验，**不能**因此报错
     */
    fun validate(prompt: JSONObject, catalog: Set<String>?): Report {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (prompt.length() == 0) {
            return Report(false, listOf("工作流是空的"), emptyList())
        }

        val parsed = runCatching { ApiPromptParser.parse(prompt) }.getOrNull()
        if (parsed == null || parsed.nodes.isEmpty()) {
            return Report(false, listOf("无法解析出任何节点——请确认这是 API 格式的工作流"), emptyList())
        }

        // 1. 节点类型是否存在（catalog 为 null 表示没查到，跳过而不是误报）
        val usedTypes = parsed.nodes.map { it.classType }
        val missing = NodeAvailability.findMissing(usedTypes, catalog)
        if (missing.isNotEmpty()) {
            errors += "服务器上没有这些节点类型：${missing.joinToString(", ")}。" +
                "请改用已安装的节点，或先在服务器上安装对应插件"
        }

        // 2. 连线引用了不存在的上游节点（手改 JSON 的头号错误）
        val knownIds = parsed.nodes.map { it.id }.toSet()
        val dangling = parsed.nodes.flatMap { node ->
            node.links
                .filter { (_, ref) -> ref.nodeId !in knownIds }
                .map { (input, ref) -> "${node.id}(${node.classType}).$input → 节点 ${ref.nodeId}（不存在）" }
        }
        if (dangling.isNotEmpty()) {
            errors += "有 ${dangling.size} 处连线指向不存在的节点：${dangling.take(5).joinToString("；")}" +
                if (dangling.size > 5) " 等" else ""
        }

        // 3. 有没有输出节点：没有就是跑完也不出图，纯浪费算力
        if (parsed.outputNodeIds.isEmpty()) {
            errors += "没有输出节点（SaveImage / PreviewImage 等）——" +
                "这样的工作流跑完不会产出图片"
        }

        // 4. 提示信息：采样器缺失通常意味着这不是一条能出图的链路
        if (parsed.nodes.none { it.classType in setOf("KSampler", "KSamplerAdvanced", "SamplerCustom") }) {
            warnings += "工作流里没有采样器节点，确认这是你要跑的图？"
        }

        return Report(errors.isEmpty(), errors, warnings)
    }
}
