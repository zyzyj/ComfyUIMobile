package com.local.comfyuimobile.network

import com.local.comfyuimobile.model.MediaKind
import com.local.comfyuimobile.model.ResultMedia
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object ResultParser {
    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "avif")
    private val videoExtensions = setOf("mp4", "webm", "mov", "mkv", "m4v")

    fun parse(baseUrl: String, history: JSONObject): List<ResultMedia> {
        val result = mutableListOf<ResultMedia>()
        history.keys().forEach { jobId ->
            val job = history.optJSONObject(jobId) ?: return@forEach
            val prompt = job.optJSONArray("prompt")
            val taskNumber = prompt?.optLong(0) ?: 0L
            val promptObj = prompt?.optJSONObject(2)
            val extraData = prompt?.optJSONObject(3)
            val mobile = extraData?.optJSONObject("comfy_mobile")
            val createdAt = extraData?.optLong("create_time")?.takeIf { it > 0 }
                ?: executionStart(job)
            val workflowPath = mobile?.optString("workflow_path").orEmpty()
            val workflowName = mobile?.optString("workflow_name").orEmpty()
            val elapsedMs = executionStart(job).let { start ->
                if (start > 0) (executionEnd(job) - start).takeIf { it > 0 } else null
            }
            val seed = extractSeed(promptObj)
            val positivePrompt = extractPositivePrompt(promptObj)
            val nodeDescriptors = workflowNodeDescriptors(extraData)
            val outputs = job.optJSONObject("outputs") ?: return@forEach
            outputs.keys().forEach { nodeId ->
                val descriptor = nodeDescriptors[nodeId]
                collect(
                    baseUrl,
                    jobId,
                    nodeId,
                    descriptor?.type.orEmpty(),
                    descriptor?.title.orEmpty(),
                    outputs.opt(nodeId),
                    createdAt,
                    taskNumber,
                    workflowPath,
                    workflowName,
                    elapsedMs,
                    seed,
                    positivePrompt,
                    result,
                )
            }
        }
        return result
            .distinctBy { "${it.jobId}/${it.nodeId}/${it.type}/${it.subfolder}/${it.filename}" }
            .sortedWith(compareByDescending<ResultMedia> { it.createdAt }.thenByDescending { it.taskNumber })
    }

    private fun collect(
        baseUrl: String,
        jobId: String,
        nodeId: String,
        nodeType: String,
        nodeTitle: String,
        value: Any?,
        createdAt: Long,
        taskNumber: Long,
        workflowPath: String,
        workflowName: String,
        elapsedMs: Long?,
        seed: String?,
        positivePrompt: String?,
        out: MutableList<ResultMedia>,
    ) {
        when (value) {
            is JSONObject -> {
                val filename = value.optString("filename")
                if (filename.isNotBlank()) {
                    val ext = filename.substringAfterLast('.', "").lowercase()
                    val kind = when (ext) {
                        in imageExtensions -> MediaKind.IMAGE
                        in videoExtensions -> MediaKind.VIDEO
                        else -> null
                    }
                    if (kind != null) {
                        val subfolder = value.optString("subfolder")
                        val type = value.optString("type", "output")
                        val url = "$baseUrl/view?filename=${encode(filename)}&subfolder=${encode(subfolder)}&type=${encode(type)}"
                        out += ResultMedia(
                            jobId = jobId,
                            nodeId = nodeId,
                            nodeType = nodeType,
                            nodeTitle = nodeTitle,
                            filename = filename,
                            subfolder = subfolder,
                            type = type,
                            kind = kind,
                            url = url,
                            createdAt = createdAt,
                            taskNumber = taskNumber,
                            workflowPath = workflowPath,
                            workflowName = workflowName,
                            elapsedMs = elapsedMs,
                            seed = seed,
                            positivePrompt = positivePrompt,
                        )
                    }
                }
                value.keys().forEach {
                    collect(baseUrl, jobId, nodeId, nodeType, nodeTitle, value.opt(it), createdAt, taskNumber, workflowPath, workflowName, elapsedMs, seed, positivePrompt, out)
                }
            }
            is JSONArray -> repeat(value.length()) {
                collect(baseUrl, jobId, nodeId, nodeType, nodeTitle, value.opt(it), createdAt, taskNumber, workflowPath, workflowName, elapsedMs, seed, positivePrompt, out)
            }
        }
    }

    private fun workflowNodeDescriptors(extraData: JSONObject?): Map<String, NodeDescriptor> {
        val nodes = extraData
            ?.optJSONObject("extra_pnginfo")
            ?.optJSONObject("workflow")
            ?.optJSONArray("nodes")
            ?: return emptyMap()
        return buildMap {
            repeat(nodes.length()) { index ->
                val node = nodes.optJSONObject(index) ?: return@repeat
                val id = node.opt("id")?.toString().orEmpty()
                val type = node.optString("type")
                if (id.isNotBlank() && type.isNotBlank()) {
                    put(id, NodeDescriptor(type, node.optString("title").ifBlank { type }))
                }
            }
        }
    }

    private fun executionStart(job: JSONObject): Long {
        val messages = job.optJSONObject("status")?.optJSONArray("messages") ?: return 0L
        repeat(messages.length()) { index ->
            val message = messages.optJSONArray(index) ?: return@repeat
            if (message.optString(0) == "execution_start") return message.optJSONObject(1)?.optLong("timestamp") ?: 0L
        }
        return 0L
    }

    private fun executionEnd(job: JSONObject): Long {
        val messages = job.optJSONObject("status")?.optJSONArray("messages") ?: return 0L
        repeat(messages.length()) { index ->
            val message = messages.optJSONArray(index) ?: return@repeat
            when (message.optString(0)) {
                "execution_success", "execution_cached" ->
                    return message.optJSONObject(1)?.optLong("timestamp") ?: 0L
            }
        }
        return 0L
    }

    /**
     * 从提交的 prompt 里取第一个种子值。
     *
     * v0.1.78：以前照抄的是 `classType.contains("Sampler")` 子串匹配——和 PromptBatch
     * 在 v0.1.72 修掉的是同一个毛病。名字里带 Sampler 的节点不一定是采样器
     * （KSamplerModel、各种第三方模型选择节点），它们被优先选中时，展示给用户的是
     * 一个跟这次出图毫无关系的数字（节点顺序靠前就算赢）。
     *
     * 现在的判定：节点 inputs 里**真的有** seed / noise_seed 才算候选，再看命名
     * （以 Sampler / SamplerAdvanced / Seed 结尾的才是采样器）。严格规则一条都没命中时
     * 退回旧的宽松匹配兜底，免得第三方采样器节点从"能显示"退化成"显示不出"。
     */
    internal fun extractSeed(prompt: JSONObject?): String? {
        val nodes = prompt?.let { obj ->
            val keys = obj.keys()
            buildList {
                while (keys.hasNext()) {
                    val node = obj.optJSONObject(keys.next()) ?: continue
                    add(node)
                }
            }
        }.orEmpty()
        return firstSeed(nodes, strict = true) ?: firstSeed(nodes, strict = false)
    }

    private fun firstSeed(nodes: List<JSONObject>, strict: Boolean): String? {
        for (node in nodes) {
            val classType = node.optString("class_type")
            val inputs = node.optJSONObject("inputs") ?: continue
            val value = inputs.opt("seed") ?: inputs.opt("noise_seed")
            val seed = value?.toString().takeUnless { it.isNullOrBlank() } ?: continue
            if (isSeedNode(classType, strict)) return seed
        }
        return null
    }

    private fun isSeedNode(classType: String, strict: Boolean): Boolean {
        if (classType.isBlank()) return false
        return if (strict) {
            classType.endsWith("Sampler", ignoreCase = true) ||
                classType.endsWith("SamplerAdvanced", ignoreCase = true) ||
                classType.endsWith("Seed", ignoreCase = true)
        } else {
            classType.contains("Sampler", ignoreCase = true) ||
                classType.contains("Seed", ignoreCase = true)
        }
    }

    /** 从提交的 prompt 里取第一个正向提示词（CLIPTextEncode 等文本节点）。 */
    private fun extractPositivePrompt(prompt: JSONObject?): String? {
        prompt?.keys()?.forEach { key ->
            val node = prompt.optJSONObject(key) ?: return@forEach
            val classType = node.optString("class_type")
            if (classType.contains("TextEncode", ignoreCase = true) || classType.contains("CLIPText", ignoreCase = true)) {
                val text = node.optJSONObject("inputs")?.optString("text")
                if (!text.isNullOrBlank()) return text
            }
        }
        return null
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private data class NodeDescriptor(val type: String, val title: String)
}
