package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.data.WorkflowFormat
import com.local.comfyuimobile.model.ResultMedia
import com.local.comfyuimobile.network.ComfyClient
import com.local.comfyuimobile.network.ResultParser
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

/**
 * [McpToolHost] 的真实实现：把 MCP 工具调用接到现有的 [ComfyClient] 上（v0.2.85）。
 *
 * 刻意复用 ViewModel 正在用的那个 `ComfyClient` 实例——baseUrl、反代 Cookie、
 * 双重编码与网关重试策略全都已经配好了，另起一个只会多一套不一致的状态。
 *
 * **`generate` 的已知边界**：只接受 **API 格式**工作流。ComfyUI 的画布格式
 * （`{nodes:[...]}`）转 API 格式走前端 `graphToPrompt()`，在本项目里依赖 WebView
 * （[com.local.comfyuimobile.bridge.ComfyBridge]），而 MCP server 跑在前台服务里、
 * 拿不到 Activity。遇到画布格式会返回可操作的错误提示，而不是静默出一张错的图。
 */
internal class ComfyMcpHost(
    private val client: ComfyClient,
    /** 出图落盘用。与 [McpToolRegistry] 同一实例（同一目录），写完直接 register。 */
    private val files: McpFileStore,
    /** 取"当前工作流路径"；用户没指定 workflow 参数时用它。 */
    private val currentWorkflowPath: () -> String?,
    /** 提交被反代网关拒绠时刷新登录 Cookie（与 App 内出图同一套）。 */
    private val refreshCookie: suspend () -> Unit,
    private val clientId: String,
    /**
     * MCP 出图同步写入结果页（v0.2.88）。
     *
     * 以前图片只进临时目录、1 小时 TTL 后删除——用户用 AI 生成的图在 App 的
     * "结果"页里找不到，说不过去。null 表示不落结果页（单测用）。
     */
    private val resultSink: (suspend (ResultMedia, File) -> Unit)? = null,
    /**
     * 提交成功后通知上层（v0.2.90）。
     *
     * MCP 出图走的不是界面那条链路，原先提交完就"消失"了：界面任务列表、通知进度
     * 都看不到它，用户只能等完成后在结果页发现——AI 在后台出图时界面完全没反馈。
     * 回调让上层把这个 promptId 纳入跟踪（写偏好 → ViewModel 观察 → 起监控）。
     */
    private val onSubmitted: (suspend (promptId: String) -> Unit)? = null,
) : McpToolHost {

    override suspend fun listModels(type: String?): String {
        requireConnected()
        val info = client.objectInfo()
        val wanted = type?.lowercase()
        val lines = mutableListOf<String>()
        if (wanted == null || wanted == "checkpoint") {
            val names = readNameList(info, "CheckpointLoaderSimple", "ckpt_name")
            if (names.isNotEmpty()) {
                lines += "checkpoints:"
                lines += names.map { "  $it" }
            }
        }
        if (wanted == null || wanted == "lora") {
            val names = readNameList(info, "LoraLoader", "lora_name")
            if (names.isNotEmpty()) {
                lines += "loras:"
                lines += names.map { "  $it" }
            }
        }
        if (lines.isEmpty()) {
            val label = when (wanted) {
                "checkpoint" -> "checkpoint"
                "lora" -> "LoRA"
                else -> "模型"
            }
            return "服务器上没有可用的 $label（或该节点类型未被安装）。"
        }
        return lines.joinToString("\n")
    }

    /**
     * 从 `/object_info` 里取某个节点某个输入的候选文件名。
     *
     * `/object_info` 的形状：`{"CheckpointLoaderSimple":{"input":{"required":{"ckpt_name":[["a.safetensors","b.safetensors"],{...}]}}}}`
     * ——候选值是该输入规格数组的第 0 项。路径任一环缺失都返回空，不抛异常
     * （不同 ComfyUI 版本/插件装的节点不一样，缺节点是常态而非错误）。
     */
    private fun readNameList(info: JSONObject, nodeType: String, inputName: String): List<String> {
        val spec = info.optJSONObject(nodeType)
            ?.optJSONObject("input")
            ?.optJSONObject("required")
            ?.optJSONArray(inputName)
            ?: return emptyList()
        val values = spec.optJSONArray(0) ?: return emptyList()
        return buildList {
            for (i in 0 until values.length()) {
                values.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    override suspend fun listWorkflows(): String {
        requireConnected()
        val entries = client.listWorkflows()
            .filter { !it.isDirectory && it.path.endsWith(".json", ignoreCase = true) }
        if (entries.isEmpty()) return "服务器上没有可用的工作流。"
        // 标出是否 API 格式：`generate` 只吃 API 格式，不标的话 AI 只能一个个试错
        // （调一次报一次错）。读文件只为判定格式，失败就当未知，不阻断列表。
        //
        // 注意不能在 joinToString 的 lambda 里调 suspend 函数——先在循环里取好格式。
        val formats = entries.map { entry ->
            runCatching {
                val root = JSONObject(client.readWorkflow(entry.path))
                if (WorkflowFormat.isApiPrompt(root)) "api" else "canvas"
            }.getOrDefault("unknown")
        }
        return entries.mapIndexed { index, entry ->
            val tag = when (formats[index]) {
                "api" -> "[可直用]"
                "canvas" -> "[画布格式，需先 Export (API)]"
                else -> "[格式未知]"
            }
            "${entry.name}  ${entry.path}  $tag"
        }.joinToString("\n")
    }

    override suspend fun generate(request: GenerateRequest, awaitMillis: Long): GenerateOutcome {
        requireConnected()
        val path = request.workflow?.takeIf { it.isNotBlank() }
            ?: currentWorkflowPath()?.takeIf { it.isNotBlank() }
            ?: return GenerateOutcome.Failed(
                "没有指定工作流，且 App 当前没有打开中的工作流；请传 workflow 参数（见 list_workflows）",
            )

        val rawWorkflow = try {
            client.readWorkflow(path)
        } catch (error: Exception) {
            return GenerateOutcome.Failed("读取工作流失败（$path）：${error.message}")
        }
        val workflowJson = runCatching { JSONObject(rawWorkflow) }.getOrNull()
            ?: return GenerateOutcome.Failed("工作流不是合法 JSON：$path")

        if (!WorkflowFormat.isApiPrompt(workflowJson)) {
            return GenerateOutcome.Failed(
                "工作流「$path」是画布格式，MCP 目前只能执行 API 格式。" +
                    "请在 ComfyUI 里用 Workflow → Export (API) 另存一份，再用它的路径调用。",
            )
        }

        val planned = when (val result = McpPromptPlanner.plan(workflowJson, request)) {
            is McpPromptPlanner.Result.Failure -> return GenerateOutcome.Failed(result.message)
            is McpPromptPlanner.Result.Ok -> result
        }
        AppLogger.info("MCP 生成：$path，已注入 ${planned.applied.joinToString(",")}")

        val name = path.substringAfterLast('/')
        val response = try {
            client.queuePrompt(
                planned.promptJson,
                rawWorkflow,
                clientId,
                path,
                name,
                refreshAuthCookie = refreshCookie,
            )
        } catch (error: Exception) {
            return GenerateOutcome.Failed("提交生成失败：${error.message}")
        }
        // 入队成功后交给上层纳入跟踪（界面任务列表/通知进度）。失败只记日志：
        // 跟踪是锦上添花，不能因为它失败就让已经入队的任务在模型那边变成失败。
        onSubmitted?.let { callback ->
            runCatching { callback(response.promptId) }
                .onFailure { AppLogger.warn("MCP 提交回调失败：${response.promptId}", it) }
        }
        return finishOrRunning(response.promptId, awaitMillis, startedAt = System.currentTimeMillis())
    }

    override suspend fun jobStatus(jobId: String): GenerateOutcome {
        requireConnected()
        // 查询时不再长等：已经知道任务在跑了，给一次短窗口即可，避免把 job_status 变成
        // 第二个会卡住的 generate。
        return finishOrRunning(jobId, awaitMillis = 0L, startedAt = System.currentTimeMillis())
    }

    /**
     * 批量查询（v0.2.90）：一次拿多个任务的状态。
     *
     * AI 提交 8 个不同任务时，逐个查要 8 轮往返——与"提交/验收分离"直接冲突。
     * 队列只取一次，多个 job 共用同一份快照，既省请求也保证彼此一致。
     */
    override suspend fun jobStatusBatch(ids: List<String>): String {
        requireConnected()
        val queue = runCatching { client.queue() }.getOrNull()
        val lines = mutableListOf<String>()
        for (id in ids.take(MAX_BATCH_IDS)) {
            val trimmed = id.trim()
            if (trimmed.isBlank()) continue
            val history = runCatching { client.history(trimmed) }.getOrNull()
            val hasOutputs = history?.optJSONObject(trimmed)
                ?.optJSONObject("outputs")?.keys()?.hasNext() == true
            val verdict = McpJobState.resolve(trimmed, queue, history, hasOutputs)
            val detail = StringBuilder()
                .append(trimmed.take(8))
                .append("  ")
                .append(verdict.phase.label)
            verdict.position?.let { detail.append(" · 位置 $it") }
            if (verdict.message.isNotBlank()) detail.append(" · ").append(verdict.message)
            lines += detail.toString()
        }
        if (lines.isEmpty()) return "没有可查询的 job_id。"
        return lines.joinToString("\n")
    }

    /**
     * 未连接 ComfyUI 时给出**可操作**的提示，而不是网络层的异常。
     *
     * 服务可以在未连接时先启动（通知也会提示），但每个工具都该明确告诉模型
     * "现在不能用、该做什么"，否则模型会把连接拒绝当成工具本身坏了。
     */
    private fun requireConnected() {
        if (client.serverUrl().isBlank()) {
            throw IllegalStateException(
                "App 尚未连接 ComfyUI：请先打开 ComfyUIMobile 连接服务器（账号页或设置），然后重试",
            )
        }
    }

    private suspend fun finishOrRunning(
        promptId: String,
        awaitMillis: Long,
        startedAt: Long,
    ): GenerateOutcome {
        val deadline = startedAt + awaitMillis
        while (true) {
            // 统一判据：先队列后历史，两处信号源必须走同一个 resolve，
            // 否则"排队中"会被 history 缺失误判成运行中/失败。
            val queue = runCatching { client.queue() }.getOrNull()
            val history = runCatching { client.history(promptId) }.getOrNull()
            val entry = history?.optJSONObject(promptId)
            val hasOutputs = entry?.optJSONObject("outputs")?.keys()?.hasNext() == true
            val verdict = McpJobState.resolve(promptId, queue, history, hasOutputs)
            val elapsedSec = (System.currentTimeMillis() - startedAt) / 1000
            when (verdict.phase) {
                McpJobState.Phase.DONE -> {
                    val media = collectMedia(promptId, history ?: JSONObject())
                    if (media.isEmpty()) {
                        return GenerateOutcome.Failed("任务 $promptId 已结束但没有取到图片")
                    }
                    return GenerateOutcome.Done(promptId, media)
                }
                McpJobState.Phase.FAILED -> {
                    val reason = entry?.let { errorSummary(it) }.orEmpty()
                    return GenerateOutcome.Failed("任务 $promptId 未产出图片：${reason.ifBlank { verdict.message }}")
                }
                McpJobState.Phase.QUEUED -> {
                    if (System.currentTimeMillis() >= deadline) {
                        return GenerateOutcome.Queued(promptId, elapsedSec, verdict.position, verdict.message)
                    }
                }
                McpJobState.Phase.RUNNING, McpJobState.Phase.UNKNOWN -> {
                    if (System.currentTimeMillis() >= deadline) {
                        return GenerateOutcome.Running(promptId, elapsedSec, verdict.message)
                    }
                }
            }
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    /** 从 history 里解析输出并**直接下载到文件**（不经过内存，见 [McpFileStore] 说明）。 */
    private suspend fun collectMedia(promptId: String, history: JSONObject): List<McpMedia> {
        val parsed = runCatching { ResultParser.parse(client.serverUrl(), history) }.getOrNull()
            ?: return emptyList()
        val images = parsed.filter { it.kind == com.local.comfyuimobile.model.MediaKind.IMAGE }
            .take(MAX_IMAGES)
        val result = mutableListOf<McpMedia>()
        for (item in images) {
            val extension = item.filename.substringAfterLast('.', "png").lowercase()
            val target = files.newFile(extension)
            val ok = runCatching {
                target.outputStream().use { client.downloadTo(item.url, it) }
            }.onFailure { AppLogger.warn("MCP 取图失败：${item.filename}", it) }.isSuccess
            // 失败或空文件都不要登记：否则 /files 会交给对方一个 0 字节的"图"。
            if (!ok || target.length() == 0L) {
                runCatching { target.delete() }
                continue
            }
            // 同步写入结果页：用户用 AI 生成的图应该和 App 内出图一样能翻到。
            // 落结果页失败不影响返回给模型（那只是锦上添花），记日志即可。
            resultSink?.let { sink ->
                runCatching { sink(item, target) }
                    .onFailure { AppLogger.warn("MCP 出图写入结果页失败：${item.filename}", it) }
            }
            result += McpMedia(
                filename = item.filename,
                extension = extension,
                contentType = McpFileStore.contentTypeOf(extension),
                file = target,
            )
        }
        return result
    }

    private fun errorSummary(job: JSONObject?): String {
        val status = job?.optJSONObject("status") ?: return "无输出"
        val messages = status.optJSONArray("messages") ?: return status.optString("status_str", "无输出")
        val text = buildString {
            for (i in 0 until messages.length()) {
                val pair = messages.optJSONArray(i) ?: continue
                if (pair.optString(0) == "execution_error") {
                    append(pair.optJSONObject(1)?.optString("exception_message").orEmpty())
                }
            }
        }
        return text.ifBlank { status.optString("status_str", "无输出") }
    }

    private companion object {
        /** 轮询间隔。ComfyUI 单图几十秒，2 秒粒度足够且不会把服务器问烦。 */
        const val POLL_INTERVAL_MILLIS = 2_000L

        /** 单次取回的最大图片数（批量出图时不让内存爆掉）。 */
        const val MAX_IMAGES = 8

        /** 批量 job_status 一次最多查多少个 id（防超长参数把请求撑爆）。 */
        const val MAX_BATCH_IDS = 16
    }
}
