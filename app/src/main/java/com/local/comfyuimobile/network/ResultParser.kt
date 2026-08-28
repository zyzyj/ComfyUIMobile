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

    /** 从提交的 prompt 里取第一个种子值（KSampler / Seed 节点）。 */
    private fun extractSeed(prompt: JSONObject?): String? {
        prompt?.keys()?.forEach { key ->
            val node = prompt.optJSONObject(key) ?: return@forEach
            val classType = node.optString("class_type")
            if (classType.contains("Sampler", ignoreCase = true) || classType.contains("Seed", ignoreCase = true)) {
                val seed = node.optJSONObject("inputs")?.opt("seed")?.toString()
                if (!seed.isNullOrBlank()) return seed
            }
        }
        return null
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
