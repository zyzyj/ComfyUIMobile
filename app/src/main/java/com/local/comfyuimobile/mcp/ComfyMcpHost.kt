package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.data.WorkflowFormat
import com.local.comfyuimobile.model.ResultMedia
import com.local.comfyuimobile.model.JobState
import com.local.comfyuimobile.model.ParameterField
import com.local.comfyuimobile.network.ComfyClient
import com.local.comfyuimobile.network.NodeAvailability
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
    /**
     * AI Studio 通道（v0.2.97）。null = 不可用（单测）。
     *
     * 单独抽一个对象而不是把 [ComfyClient] 改成什么都能干：ComfyUI 反代与 AI Studio
     * 平台是两套完全不同的鉴权（前者靠 cookie 里的 ide-proxy，后者靠 BDUSS+bdToken），
     * 混在一个 client 里很容易把一边的凭据带到另一边去。
     */
    private val aiStudio: AiStudioBridge? = null,
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
        // v0.2.97：优先用内联 JSON（A1.1）——AI 自己搭/改的工作流不必先存盘，
        // 也绕开了"`/v2/userdata` 在反代下未必可用"这个不确定性。
        val inline = request.workflowJson?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()
                ?.takeIf { WorkflowFormat.isApiPrompt(it) }
                ?.let { WorkflowSource(INLINE_WORKFLOW_PATH, raw, it) }
        }
        if (request.workflowJson != null && inline == null) {
            return GenerateOutcome.Failed(
                "传入的 workflow_json 不是合法的 API 格式工作流。" +
                    "请确认它是 {{节点id: {{class_type, inputs}}}} 结构（ComfyUI 的 Export (API)），" +
                    "或先用 validate_workflow 预检",
            )
        }
        val source = inline ?: readApiWorkflow(request.workflow)
            ?: return GenerateOutcome.Failed(
                "没有可用的 API 格式工作流：可传 workflow_json 内联，或传 workflow 路径" +
                    "（见 list_workflows / describe_workflow）",
            )
        val path = source.path
        val workflowJson = source.json

        val planned = when (val result = McpPromptPlanner.plan(workflowJson, request)) {
            is McpPromptPlanner.Result.Failure -> return GenerateOutcome.Failed(result.message)
            is McpPromptPlanner.Result.Ok -> result
        }

        // 任意参数注入（steps/cfg/采样器/种子/尺寸…）。未识别的 key 必须报错——
        // 静默忽略会让模型以为改了其实没改。
        val params = McpPromptPlanner.applyParams(JSONObject(planned.promptJson), request.params)
        if (params.unknownKeys.isNotEmpty()) {
            return GenerateOutcome.Failed(
                "以下参数在工作流里找不到：${params.unknownKeys.joinToString(", ")}。" +
                    "请先用 describe_workflow 查看可用的 key",
            )
        }
        val finalPrompt = params.promptJson
        AppLogger.info(
            "MCP 生成：$path，已注入 ${(planned.applied + params.applied).joinToString(",")}",
        )

        val name = path.substringAfterLast('/')
        val response = try {
            client.queuePrompt(
                finalPrompt,
                source.raw,
                clientId,
                path,
                name,
                // 刻上来源：之后从 /queue 读回时能区分是不是 MCP 提交的，
                // cancel_jobs 才敢只清自己那部分。
                origin = ORIGIN_MCP,
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

    override suspend fun validateWorkflow(workflow: String?): String {
        requireConnected()
        val source = readApiWorkflow(workflow)
        if (source == null) {
            // 画布格式也是"不能提交"的一种，直接说清而不是笼统报错。
            val path = workflow?.takeIf { it.isNotBlank() } ?: currentWorkflowPath().orEmpty()
            return "预检未通过：工作流${if (path.isBlank()) "" else "「${path.substringAfterLast('/')}」"}" +
                "不是 API 格式（或无法读取）。请用 ComfyUI 的 Workflow → Export (API) 导出后再试。"
        }
        // 节点清单拿不到时不报错（catalog = null → 跳过节点存在性校验），
        // 否则网络抖动会被误报成"工作流有问题"。
        val catalog = runCatching { client.objectInfo() }
            .onFailure { AppLogger.warn("预检时读 /object_info 失败，跳过节点存在性校验", it) }
            .getOrNull()
            ?.let { NodeAvailability.parseCatalog(it.toString()) }
        val report = McpWorkflowValidator.validate(source.json, catalog)
        AppLogger.info(
            "MCP 工作流预检：${source.path}，结果=${if (report.ok) "通过" else "${report.errors.size} 项错误"}",
        )
        return report.render()
    }

    /**
     * 列出工作流可调参数（v0.2.91）。
     *
     * 此前 `generate` 只有 prompt/negative/checkpoint/lora/count——**尺寸、steps、cfg、
     * 采样器、seed 全动不了**，AI 说"帮我调一下"无从下手。这个工具让它第一次能
     * "看见"工作流里有什么可调。
     */
    override suspend fun describeWorkflow(workflow: String?): String {
        requireConnected()
        val source = readApiWorkflow(workflow)
            ?: return "当前没有可用的 API 格式工作流（请传 workflow 参数，或先在 App 里打开一个）"
        val path = source.path
        val fields = McpPromptPlanner.fields(source.json)
        if (fields.isEmpty()) {
            return "工作流「${path.substringAfterLast('/')}」没有可调字段（可能全是连线输入）。"
        }
        val lines = mutableListOf<String>()
        lines += "工作流：${path.substringAfterLast('/')}（$path）"
        lines += "可调字段 ${fields.size} 项，用 generate 的 params 传入 key 即可修改："
        // 常用项排前面：模型最常改的就是这几类，让他第一屏就看到。
        // v0.2.95：排序规则抽到 McpPromptPlanner.orderForDisplay（以前内联实现
        // 用 compareByDescending，顺序恰好是反的，已加单测锁住）。
        val ordered = McpPromptPlanner.orderForDisplay(fields)
        for (field in ordered.take(MAX_DESCRIBE_FIELDS)) {
            val range = when {
                field.options.isNotEmpty() -> "可选：${field.options.take(12).joinToString(" | ")}"
                field.minimum != null || field.maximum != null ->
                    "范围：${field.minimum ?: "-"} ~ ${field.maximum ?: "-"}"
                else -> ""
            }
            lines += "  ${field.key}  [${field.name}]  ${field.label.ifBlank { field.name }}" +
                "  当前=${field.displayValue}" +
                (if (range.isBlank()) "" else "  $range")
        }
        if (fields.size > MAX_DESCRIBE_FIELDS) {
            lines += "  …还有 ${fields.size - MAX_DESCRIBE_FIELDS} 项未列出"
        }
        return lines.joinToString("\n")
    }

    /**
     * 读一个工作流并确认是 API 格式。返回 (路径, 解析后的 JSON)。
     * 与 [generate] 共用——判定标准只写一份，不然两边又会对不上。
     */
    private suspend fun readApiWorkflow(workflow: String?): WorkflowSource? {
        val path = workflow?.takeIf { it.isNotBlank() }
            ?: currentWorkflowPath()?.takeIf { it.isNotBlank() }
            ?: return null
        val raw = runCatching { client.readWorkflow(path) }.getOrNull() ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return if (WorkflowFormat.isApiPrompt(json)) WorkflowSource(path, raw, json) else null
    }

    /** 读到的 API 格式工作流：路径、原始文本（提交时回填 extra_data）、解析后的对象。 */
    private data class WorkflowSource(val path: String, val raw: String, val json: JSONObject)

    override suspend fun cancelJobs(jobId: String?, includeOthers: Boolean): String {
        requireConnected()
        if (jobId != null) {
            val queue = runCatching { client.queue() }.getOrNull()
                ?: return "读取队列失败，未能中止 $jobId"
            val job = queue.firstOrNull { it.id == jobId }
                ?: return "队列里没有 $jobId（可能已执行完或已被清理）；如要清空队列，请不传 job_id"
            runCatching { client.cancel(job) }
                .onFailure { return "中止 $jobId 失败：${it.message}" }
            return "已请求中止 $jobId。"
        }

        // 不指定 jobId：默认**只动本 App 提交的**（App 界面 + MCP 两条路径）。
        //
        // 不直接调 clearPending()——那是清空**整个**队列，会连带砍掉用户自己在
        // App 里提交的图、或网页端正在跑的任务。AI 一句”算了重新来“就把它们清掉，
        // 正是”AI 不知道自己的任务边界“那一类问题；边界必须由App 侧划定。
        val queue = runCatching { client.queue() }.getOrNull()
            ?: return "读取队列失败，未执行任何清理"
        val mine = McpJobState.selectCancellable(queue, includeOthers)
        val others = queue.size - mine.size
        if (mine.isEmpty()) {
            return buildString {
                append("队列里没有本 App 提交的任务")
                if (others > 0) append("（另有 $others 个非本 App 提交的任务，未动它们）")
                append("。")
            }
        }
        var cancelled = 0
        for (job in mine) {
            if (runCatching { client.cancel(job) }.isSuccess) cancelled++
        }
        return buildString {
            append("已请求取消 $cancelled/${mine.size} 个本 App 提交的任务")
            if (others > 0) {
                append("；另有 $others 个非本 App 提交的任务保留未动")
                append("（确实要一起清，请传 all=true）")
            }
            append("。注意：正在执行的那一张可能仍会产出图片")
            append("（ComfyUI 在当前步骤结束后才停）。")
        }
    }

    /**
     * 等待 ComfyUI 就绪（v0.2.97）。
     *
     * 探测方式用 `/system_stats`：它最轻，且是 ComfyUI 自己实现的接口——
     * 比拿 `list_models` 试探准（后者要拉几 MB 的 object_info）。
     */
    override suspend fun waitForComfy(timeoutSeconds: Int): String {
        if (client.serverUrl().isBlank()) {
            return "App 尚未连接 ComfyUI 地址。请先在 App 里连接（或启动云端环境后回到 App 连接），" +
                "然后再调用本工具。"
        }
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        var attempts = 0
        val startedAt = System.currentTimeMillis()
        while (true) {
            attempts++
            val ok = runCatching { client.systemStats() }.isSuccess
            if (ok) {
                val waited = (System.currentTimeMillis() - startedAt) / 1000
                AppLogger.info("MCP 等待 ComfyUI：第 $attempts 次探测成功，等了 ${waited}s")
                return "ComfyUI 已就绪（等了 ${waited} 秒，探测 $attempts 次）。可以提交出图了。"
            }
            if (System.currentTimeMillis() >= deadline) {
                // 超时不自作主张：说清"没等到"，并给出下一步该做什么。
                return "等待 ${timeoutSeconds} 秒后 ComfyUI 仍不可达（探测 $attempts 次）。" +
                    "可能原因：① 云端项目未启动 ② 启动脚本还没跑完 ③ 地址/登录态不对。" +
                    "建议：确认项目已启动后重试，或先检查启动脚本的日志。"
            }
            delay(WAIT_FOR_COMFY_POLL_MILLIS)
        }
    }

    /** AI Studio 通道不可用时的统一话术（单测环境或未注入）。 */
    private fun requireAiStudio(): AiStudioBridge = aiStudio
        ?: throw IllegalStateException("AI Studio 通道未启用（当前运行环境不支持）。")

    override suspend fun listAiStudioProjects(): String = requireAiStudio().listProjects()

    override suspend fun gpuStatus(projectId: String): String = requireAiStudio().gpuStatus(projectId)

    override suspend fun listGpuOptions(projectId: String): String =
        requireAiStudio().listGpuOptions(projectId)

    override suspend fun startGpu(projectId: String, schedule: String?): String =
        requireAiStudio().startGpu(projectId, schedule)

    override suspend fun stopGpu(projectId: String): String = requireAiStudio().stopGpu(projectId)

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
        // v0.2.97：这里是 Python 完整 traceback（几千字符），原样返回既费 token、
        // 又把真正有用的异常行淹没。摘要化到末尾几行（见 McpErrorSummary）。
        val summarized = McpErrorSummary.summarize(text)
        return summarized.ifBlank { status.optString("status_str", "无输出") }
    }

    private companion object {
        /** 轮询间隔。ComfyUI 单图几十秒，2 秒粒度足够且不会把服务器问烦。 */
        const val POLL_INTERVAL_MILLIS = 2_000L

        /** 单次取回的最大图片数（批量出图时不让内存爆掉）。 */
        const val MAX_IMAGES = 8

        /** 批量 job_status 一次最多查多少个 id（防超长参数把请求撑爆）。 */
        const val MAX_BATCH_IDS = 16

        /** describe_workflow 一次最多列多少字段（太长会挤爆模型上下文）。 */
        const val MAX_DESCRIBE_FIELDS = 40

        /** 本 App 的 MCP 通道提交任务时写到 extra_data 的来源标记。 */
        const val ORIGIN_MCP = "mcp"

        /** 内联工作流在 extra_data 里记的路径标识（没有真实文件路径）。 */
        const val INLINE_WORKFLOW_PATH = "(inline)"

        /** wait_for_comfy 的探测间隔：2 秒足够密，又不会把服务器问烦。 */
        const val WAIT_FOR_COMFY_POLL_MILLIS = 2_000L
    }
}
