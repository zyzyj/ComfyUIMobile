package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.data.AuthCookieProvider
import com.local.comfyuimobile.data.WorkflowFormat
import com.local.comfyuimobile.model.ResultMedia
import com.local.comfyuimobile.model.JobState
import com.local.comfyuimobile.model.ParameterField
import com.local.comfyuimobile.network.AiStudioKernelClient
import com.local.comfyuimobile.network.AiStudioProtocol
import com.local.comfyuimobile.network.ComfyClient
import com.local.comfyuimobile.network.NodeAvailability
import com.local.comfyuimobile.network.ResultParser
import android.util.Base64
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
    /**
     * 终端能力（v0.2.97）。null = 不可用（单测）。
     *
     * 它需要“当前账号 + 项目”，与 [aiStudio] 同源；分开注入是因为它的安全模型
     * 与依赖（WebSocket 长连、每终端一把锁）自成一块，混在一起更难测。
     */
    private val terminal: McpTerminalHost? = null,
    /**
     * 读「已提交任务记录」（v0.3.7）。
     *
     * 与 [onSubmitted] 写的是**同一份存储**（界面任务跟踪也在用）——分成两份
     * 必然出现「界面里有、AI 查不到」的平行路径。null = 不提供（单测）。
     */
    private val submittedJobsReader: (suspend () -> List<com.local.comfyuimobile.data.SubmittedJobRecord>)? = null,
    /** 把任务标为「已取图」（v0.3.7）。null = 不提供（单测）。 */
    private val submittedJobFetched: (suspend (String) -> Unit)? = null,
) : McpToolHost {

    override suspend fun listModels(type: String?): String {
        requireConnected()
        val info = client.objectInfo()
        val wanted = type?.lowercase()?.trim()
        val lines = mutableListOf<String>()
        // v0.3.21：type 入参按 ComfyUI 节点 input 映射，不再只认 checkpoint/lora。
        // 真机报告 Bug2：传 unet/vae/clip 等返回与 loras 完全一致——因为旧实现
        // 只匹配 checkpoint 和 lora，其它全部落到 else 分支走默认逻辑。
        val aliases = mapOf(
            "checkpoint" to ModelRef("CheckpointLoaderSimple", "ckpt_name", "checkpoints"),
            "checkpoints" to ModelRef("CheckpointLoaderSimple", "ckpt_name", "checkpoints"),
            "lora" to ModelRef("LoraLoader", "lora_name", "loras"),
            "loras" to ModelRef("LoraLoader", "lora_name", "loras"),
            "unet" to ModelRef("UNETLoader", "unet_name", "unet"),
            "diffusion_models" to ModelRef("UNETLoader", "unet_name", "unet"),
            "diffusion_model" to ModelRef("UNETLoader", "unet_name", "unet"),
            "vae" to ModelRef("VAELoader", "vae_name", "vae"),
            "clip" to ModelRef("CLIPLoader", "clip_name", "clip"),
        )
        if (wanted != null && wanted in aliases) {
            val ref = aliases.getValue(wanted)
            val names = readNameList(info, ref.nodeType, ref.inputName)
            if (names.isNotEmpty()) {
                lines += "${ref.label}:"
                lines += names.map { "  $it" }
            }
        } else if (wanted != null) {
            // v0.3.22（二轮 Bug6）：非法 type 明确报错并列合法值，而不是
            // 静默返回全部类型（那会让人误以为 type 生效了）。
            throw IllegalArgumentException(
                "未知的 type=\"$wanted\"。合法值：checkpoint, lora, unet, diffusion_models, vae, clip。" +
                    "省略 type 则全部列出。",
            )
        } else {
            // 未指定 type：全部合并输出（checkpoint / lora / unet / vae / clip）。
            for (ref in listOf(
                ModelRef("CheckpointLoaderSimple", "ckpt_name", "checkpoints"),
                ModelRef("LoraLoader", "lora_name", "loras"),
                ModelRef("UNETLoader", "unet_name", "unet"),
                ModelRef("VAELoader", "vae_name", "vae"),
                ModelRef("CLIPLoader", "clip_name", "clip"),
            )) {
                val names = readNameList(info, ref.nodeType, ref.inputName)
                if (names.isNotEmpty()) {
                    lines += "${ref.label}:"
                    lines += names.map { "  $it" }
                }
            }
        }
        if (lines.isEmpty()) {
            val label = wanted ?: "模型"
            return "服务器上没有可用的 $label（或对应节点类型未被安装）。"
        }
        // v0.2.99（计划书 2.1 ③）：下载完模型/LoRA 后**必须重启 ComfyUI**，
        // 否则它不会重新扫目录、新文件不会出现在这份列表里。
        return lines.joinToString("\n") +
            "\n提示：以上列表来自 ComfyUI 启动时扫描的结果。若刚下载过模型/LoRA 但这里没看到，" +
            "**需要重启 ComfyUI**（否则它不会重新扫目录）：在跑 ComfyUI 的那条终端上" +
            "先 terminal_interrupt 发 Ctrl+C，再重跑启动脚本，然后 wait_for_comfy。"
    }

    private data class ModelRef(val nodeType: String, val inputName: String, val label: String)

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
        // P1-2：/v2/userdata 在 AI Studio 反代下未必可用。失败时给**明确引导**
        // （选项 B），而不是把网络层异常丢给模型、或去猜容器里的目录路径
        // （各项目布局不同，猜错反而误导——真机实测 AI 就是这样自己找路径并
        //  转格式后用 workflow_json 内联提交的）。
        val entries = runCatching { client.listWorkflows() }
            .getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                AppLogger.warn("list_workflows 失败（回退到引导语）", error)
                return "无法从这里列出服务器工作流（该平台的反代可能不支持 /v2/userdata）：" +
                    "${error.message ?: "未知错误"}\n" +
                    "继续出图的两种做法：\n" +
                    "  1. 直接传 workflow_json 内联（需 API 格式，推荐）——用 validate_workflow 预检后再 generate\n" +
                    "  2. 传 workflow 路径（如果你知道服务器上确切的文件路径）\n" +
                    "本平台不支持自动列出，请优先用 workflow_json 内联。"
            }
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
        }.joinToString("\n") +
            "\n提示：传 workflow 时用上面第二列的完整路径；也可直接把 API 格式 JSON 用 workflow_json 内联。"
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
            // v0.3.21（真机报告 Bug11）：区分"JSON 本身坏了"和"不是 API 格式"，
            // 并带具体解析错误（行列信息），而不是笼统说"不是合法 API 格式"。
            val parseError = runCatching { JSONObject(request.workflowJson!!) }
                .exceptionOrNull()?.message
            val detail = if (parseError != null) {
                "\nJSON 解析失败：$parseError"
            } else {
                "\nJSON 能解析，但不是 API 格式（{{节点id: {{class_type, inputs}}}} 结构）"
            }
            return GenerateOutcome.Failed(
                "传入的 workflow_json 无效。" + detail +
                    "请确认它是 ComfyUI 的 Export (API) 导出的 JSON，且是 UTF-8 字符串（不是对象）",
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
            // v0.3.21（真机报告 Bug6）：key 必须带「节点ID::字段名」前缀，
            // 裸 key（如 width）会找不到。报错里直接给出可用 key 样例，
            // 不再只甩一句"先用 describe_workflow"——那会陷入死循环。
            val availableKeys = McpPromptPlanner.fields(JSONObject(planned.promptJson))
                .map { it.key }
                .take(20)
            val sample = if (availableKeys.isNotEmpty()) {
                "\n可用 key（形如 节点ID::字段名）：${availableKeys.joinToString(", ")}"
            } else ""
            return GenerateOutcome.Failed(
                "以下参数在工作流里找不到：${params.unknownKeys.joinToString(", ")}。" +
                    "key 需写成「节点ID::字段名」形式，例如 19::steps、154::width。" +
                    sample,
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
            // v0.3.21（真机报告 Bug4）：PromptSubmissionException 里已经解析了
            // node_errors（节点 id + 输入名 + 值域问题），必须透传给模型——
            // 否则只有一句"请查看标红的部件"，MCP 场景没有 UI 可看。
            val detail = if (error is com.local.comfyuimobile.network.PromptSubmissionException &&
                error.nodeProblems.isNotEmpty()
            ) {
                "\n" + error.nodeProblems.entries.joinToString("\n") { (nodeId, problems) ->
                    "  节点 $nodeId：${problems.joinToString("；")}"
                }
            } else ""
            return GenerateOutcome.Failed("提交生成失败：${error.message}$detail")
        }
        // 入队成功后交给上层纳入跟踪（界面任务列表/通知进度）。失败只记日志：
        // 跟踪是锦上添花，不能因为它失败就让已经入队的任务在模型那边变成失败。
        onSubmitted?.let { callback ->
            runCatching { callback(response.promptId) }
                .onFailure { AppLogger.warn("MCP 提交回调失败：${response.promptId}", it) }
        }
        return finishOrRunning(response.promptId, awaitMillis, startedAt = System.currentTimeMillis())
    }

    override suspend fun validateWorkflow(workflow: String?, workflowJson: String?): String {
        requireConnected()
        val source = resolveWorkflowSource(workflow, workflowJson)
        if (source == null) {
            // 画布格式也是"不能提交"的一种，直接说清而不是笼统报错。
            val path = workflow?.takeIf { it.isNotBlank() } ?: currentWorkflowPath().orEmpty()
            val what = if (workflowJson != null) "传入的 workflow_json" else
                "工作流${if (path.isBlank()) "" else "「${path.substringAfterLast('/')}」"}"
            return "预检未通过：$what 不是 API 格式（或无法读取）。" +
                "请用 ComfyUI 的 Workflow → Export (API) 导出后再试。"
        }
        // 节点清单拿不到时不报错（catalog = null → 跳过节点存在性校验），
        // 否则网络抖动会被误报成"工作流有问题"。
        // v0.3.21：同时保留完整 object_info 用于 COMBO 值域校验（真机报告 Bug3）。
        val objectInfo = runCatching { client.objectInfo() }
            .onFailure { AppLogger.warn("预检时读 /object_info 失败，跳过节点存在性与值域校验", it) }
            .getOrNull()
        val catalog = objectInfo?.let { NodeAvailability.parseCatalog(it.toString()) }
        val report = McpWorkflowValidator.validate(source.json, catalog, objectInfo)
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
    override suspend fun describeWorkflow(workflow: String?, workflowJson: String?): String {
        requireConnected()
        // v0.3.21（真机报告 Bug11）：workflowJson 传了但 JSON 解析失败时，
        // 明确报解析错误，而不是笼统说"没有可用工作流"（让人误以为参数没传）。
        workflowJson?.takeIf { it.isNotBlank() }?.let { raw ->
            if (runCatching { JSONObject(raw) }.isFailure) {
                val err = runCatching { JSONObject(raw) }.exceptionOrNull()?.message
                return "workflow_json 解析失败：${err ?: "未知错误"}。" +
                    "请确认它是 UTF-8 字符串（不是对象），且是合法的 ComfyUI Export (API) JSON。"
            }
        }
        val source = resolveWorkflowSource(workflow, workflowJson)
            ?: return "当前没有可用的 API 格式工作流（可传 workflow_json 内联，或传 workflow 路径，" +
                "或先在 App 里打开一个）"
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

    /**
     * 解析工作流来源：内联 JSON 优先，否则读文件（v0.2.98）。
     *
     * P1-1：`describe_workflow` / `validate_workflow` 原先只认 `workflow`（路径），
     * 而 `generate` 支持 `workflow_json`。于是 AI 用内联 JSON 时**无法预检**——
     * 只能直接 generate，而 `validate_workflow` 存在的全部意义就是"提交前预检、
     * 省算力卡"，设计意图直接落空（真机实测 AI 正是这么干的）。
     * 三个工具现在共用这一份判定，不会再出现能力不一致。
     */
    private suspend fun resolveWorkflowSource(workflow: String?, workflowJson: String?): WorkflowSource? {
        val inline = workflowJson?.takeIf { it.isNotBlank() }?.let { raw ->
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@let null
            if (WorkflowFormat.isApiPrompt(json)) WorkflowSource(INLINE_WORKFLOW_PATH, raw, json) else null
        }
        return inline ?: readApiWorkflow(workflow)
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
        // v0.2.97：先尝试自动接入。以前只探测"App 里配好的地址"，于是 AI 启动完
        // GPU 还得让用户回 App 连一次 ComfyUI（F9 要求全程不用介入）。
        // 能从项目 endpoint 推出来的话，就直接把地址与项目级 Cookie 装上再探。
        val attached = runCatching { autoAttachComfy() }.getOrNull()
        if (client.serverUrl().isBlank()) {
            return "尚未连接 ComfyUI，也没能自动推出地址（${attached ?: "无可用项目"}）。" +
                "请先用 list_projects / start_gpu 启动一个项目，或在 App 里手动连接 ComfyUI 后重试。"
        }
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        var attempts = 0
        var backoffIndex = 0
        val startedAt = System.currentTimeMillis()
        while (true) {
            attempts++
            val ok = runCatching { client.systemStats() }.isSuccess
            if (ok) {
                val waited = (System.currentTimeMillis() - startedAt) / 1000
                AppLogger.info("MCP 等待 ComfyUI：第 $attempts 次探测成功，等了 ${waited}s")
                val where = client.serverUrl()
                val how = if (attached == null) "（用 App 里已配好的地址）" else "（$attached）"
                return "ComfyUI 已就绪 $how：$where（等了 ${waited} 秒，探测 $attempts 次）。可以提交出图了。"
            }
            if (System.currentTimeMillis() >= deadline) {
                // 超时不自作主张：说清"没等到"，并给出下一步该做什么。
                // P1-3：真机实测最常见的真实原因是**启动脚本还没跑**（GPU 就绪 ≠ ComfyUI 就绪），
                // 所以把它放在第一位，而不是笼统地列几个"可能原因"。
                return "等待 ${timeoutSeconds} 秒后 ComfyUI 仍不可达（探测 $attempts 次，地址 ${client.serverUrl()}）。\n" +
                    "最可能原因：**ComfyUI 进程还没启动**——GPU 就绪不等于 ComfyUI 就绪。\n" +
                    "请先用 terminal_exec 运行 ComfyUI 启动脚本（路径各人不同，用 ls / find 先找），" +
                    "再调本工具；命令还在跑就用 terminal_read 看日志。\n" +
                    "其他可能：登录态失效（回 App 重新登录）。"
            }
            // v0.3.23：退避探测（2s→4s→8s→10s）。ComfyUI 冷启动动辄几十秒，
            // 2 秒密集探测只会白烧平台请求（封 IP 风险），退避后最多多等一个间隔。
            delay(mcpPollBackoffMillis(backoffIndex))
            backoffIndex++
        }
    }

    /**
     * 从运行中的项目推出 ComfyUI 地址并装上 Cookie（v0.2.97）。
     *
     * 返回一句"从哪推出来的"说明；推不出来返回 null（调用方会回退到"用偏好里的地址"）。
     * 前提是**项目正在运行**：没跑的话 baseinfo 回的是空 baseUrl，也没有 api_serving 可探。
     */
    private suspend fun autoAttachComfy(): String? {
        val terminalHost = terminal ?: return null
        val (account, project) = terminalHost.runningProjectOrNull() ?: return null
        val endpoint = terminalHost.endpointFor(account, project.projectId) ?: return null
        val url = comfyUiUrlOf(endpoint) ?: return null
        // 项目级 Cookie（ide-proxy / user-{uid}-{pid}）才是 api_serving 反代真正校验的，
        // 账号 Cookie（BDUSS）不够——与界面"连终端后自动连 ComfyUI"同一条链路。
        runCatching { terminalHost.warmUpCookies(account, endpoint) }
        val cookie = terminalHost.exportCookies()
        client.setServer(url)
        client.setAuthCookie(cookie)
        AuthCookieProvider.current = cookie
        AppLogger.info("MCP 已自动接入 ComfyUI：项目 ${project.projectId}")
        return "自动接入项目 ${project.displayName()} 的 api_serving"
    }

    /**
     * 由项目 endpoint 拼出 api_serving 的 8188 地址。
     *
     * 与界面 [com.local.comfyuimobile.MainViewModel] 里的 comfyUiUrl 同一规则：
     * 平台强制 https、必须显式带 443（不带端口会被规范化逻辑补成 8188 而拼错地址）。
     */
    private fun comfyUiUrlOf(endpoint: AiStudioKernelClient.KernelEndpoint): String? {
        val base = endpoint.baseUrl.ifBlank { endpoint.basePath }
        if (base.isBlank()) return null
        val absolute = if (base.startsWith("http")) base
        else AiStudioProtocol.BASE_URL + "/" + base.trim('/')
        val secured = when {
            absolute.startsWith("http://") -> "https://" + absolute.removePrefix("http://")
            absolute.startsWith("https://") -> absolute
            else -> "https://$absolute"
        }
        return secured.trimEnd('/') + "/api_serving/8188"
    }

    /** AI Studio 通道不可用时的统一话术（单测环境或未注入）。 */
    private fun requireAiStudio(): AiStudioBridge = aiStudio
        ?: throw IllegalStateException("AI Studio 通道未启用（当前运行环境不支持）。")

    override suspend fun listAiStudioProjects(): String = requireAiStudio().listProjects()

    override suspend fun listAccounts(): String = requireAiStudio().listAccounts()

    override suspend fun switchAccount(accountId: String): String =
        requireAiStudio().switchAccount(accountId)

    override suspend fun gpuStatus(projectId: String): String = requireAiStudio().gpuStatus(projectId)

    override suspend fun listGpuOptions(projectId: String): String =
        requireAiStudio().listGpuOptions(projectId)

    override suspend fun startGpu(projectId: String, schedule: String?): String =
        requireAiStudio().startGpu(projectId, schedule)

    override suspend fun stopGpu(projectId: String): String = requireAiStudio().stopGpu(projectId)

    private fun requireTerminal(): McpTerminalHost = terminal
        ?: throw IllegalStateException("终端能力未启用（当前运行环境不支持）。")

    override suspend fun terminalList(): String = requireTerminal().list()

    override suspend fun terminalExec(
        command: String,
        terminal: String?,
        timeoutSeconds: Int?,
        background: Boolean,
    ): String = requireTerminal().exec(command, terminal, timeoutSeconds, background)

    override suspend fun terminalRead(terminal: String?, maxLines: Int?): String =
        requireTerminal().read(terminal, maxLines)

    override suspend fun terminalInterrupt(terminal: String?): String =
        requireTerminal().interrupt(terminal)

    /**
     * 取云端已存在的任意文件（v0.3.26）。
     *
     * 走终端通道把文件 base64 出来，在 App 侧解码登记后返回**文件 id**
     * （URL 由 [McpToolRegistry] 拼——它才有 fileUrlBase）。
     *
     * 不是万能方案：base64 会让数据量翻倍，且终端输出有长度限制。
     * 所以对超过 [FETCH_REMOTE_MAX_BYTES] 的文件**不做硬试**，而是给可执行的替代方案
     * （外部报告实测 179MB 批量产物就是撞在这里）。
     *
     * @return 成功时返回 `RemoteFetchResult.Saved(fileId, size, path)`，
     *   不适用时返回 `RemoteFetchResult.Text(提示文本)`。
     */
    override suspend fun fetchRemote(path: String, maxBytes: Long?): RemoteFetchResult {
        val terminalHost = requireTerminal()
        val target = path.trim()
        if (target.isBlank()) throw IllegalArgumentException("缺少 path。")
        val limit = (maxBytes ?: FETCH_REMOTE_MAX_BYTES).coerceIn(1L, FETCH_REMOTE_MAX_BYTES)

        // 先查大小：不存在 / 是目录 / 太大，三种情况都在下载前区分。
        //
        // v0.3.30（真机反馈，真因）：旧命令用了**嵌套双引号**：
        //   echo "SIZE=$(wc -c < "$target" ...)"
        // shell 解析时会在这里断开引号，整条命令变成乱码——实测直接报
        //   /bin/sh: can't open "...": no such file
        // 于是**任何路径都报 MISSING**（与 symlink 无关；`-f` 本来就跟随 symlink）。
        // 上一版改 -e 是误判。现在改用单引号包路径（路径里不会有单引号），
        // 并避免任何嵌套引号。
        val q = "'" + target.replace("'", "'\\''") + "'"
        val probe = runCatching {
            terminalHost.exec(
                "if [ -d $q ]; then echo DIR; " +
                    "elif [ -e $q ]; then echo SIZE=$(wc -c < $q 2>/dev/null || echo 0); " +
                    "echo REAL=$(readlink -f $q 2>/dev/null); " +
                    "else echo MISSING; fi",
                terminal = null,
                timeoutSeconds = 15,
            )
        }.getOrElse { error -> throw IllegalStateException("探测云端文件失败：${error.message.orEmpty().take(200)}", error) }
        val probeText = probe.trim()
        val missing = probeText.contains("MISSING")
        val isDir = probeText.contains("DIR")
        val size = Regex("SIZE=(\\d+)").find(probeText)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val realPath = Regex("REAL=(.+)").find(probeText)?.groupValues?.getOrNull(1)?.trim().orEmpty()

        if (missing) {
            return RemoteFetchResult.Text(
                "云端不存在该文件：$target\n" +
                    "提醒：本工具走 terminal_exec 的**同一个云端文件系统**（不是 App 手机端文件），" +
                    "路径必须是在云端终端里 `ls` 能看到的那个。若你刚在 terminal_exec 里确认过存在，" +
                    "把那个完整路径原样传进来（不要用手机侧的路径）。",
            )
        }
        if (isDir) {
            return RemoteFetchResult.Text(
                buildString {
                    appendLine("$target 是目录，fetch_remote 只取单个文件。两种做法：")
                    appendLine("1) 云端先打包，再用 fetch_remote 取单个 tar：")
                    appendLine("   terminal_exec: cd ${target.substringBeforeLast('/', target)} && tar -cf /tmp/pack.tar ${target.substringAfterLast('/')}")
                    appendLine("2) 逐个取（每个文件一次调用，适合少量文件）：先 ls 拿到文件名再逐个 fetch_remote")
                    append("注意：tar 体积接近原始总量，若超过 ${limit / 1024 / 1024}MB 同样取不回来，见下方大文件说明。")
                },
            )
        }
        if (size != null && size > limit) {
            // v0.3.27：大文件**自动代下**，不再让 AI 自己拼 curl + Cookie。
            //
            // 真机前测的教训：AI 拿不到项目级 Cookie（ide-proxy / user-<uid>-<pid>），
            // 它按提示自己跑 curl 必然 302 到登录页。而手机侧本来就有这个 Cookie——
            // 所以正确分工是：AI 只发一条命令起 HTTP 服务，剩下的手机自己做。
            return downloadViaApiServing(terminalHost, target, size, limit)
        }

        // v0.3.29（真机反馈，比原报告更严重）：**base64 走终端必然被截断**。
        //
        // 终端返回给模型的输出上限是 TerminalShell.MAX_OUTPUT_CHARS = 16000 字符，
        // 而一张 1.3MB 的图 base64 后约 1.8MB 文本。旧代码只在 payload.length < 16
        // 时报错，于是 16000 字符的**截断结果**会被当成完整数据解码写盘——
        // 得到一个能写出、但打不开的坏图片，而且报「成功」。
        //
        // 这是静默数据损坏，比报错严重得多。现在：
        //   1. 先用文件大小算出 base64 后长度，超限直接拒绝走 base64；
        //   2. 拿到的文本长度接近上限时也当截断处理，不写盘。
        val base64Len = size?.let { (it + 2) / 3 * 4 } ?: Long.MAX_VALUE
        if (size == null || base64Len > TERMINAL_SAFE_BASE64_CHARS) {
            // 不知道大小或必然截断：不冒险，走代下（>8MB 也在这条分支）。
            return downloadViaApiServing(
                terminalHost,
                realPath.ifBlank { target },
                size ?: 0L,
                limit,
            )
        }
        val b64 = runCatching {
            terminalHost.exec(
                "base64 -w0 $q",
                terminal = null,
                timeoutSeconds = 60,
            )
        }.getOrElse { error -> throw IllegalStateException("读取云端文件失败：${error.message.orEmpty().take(200)}", error) }
        val payload = b64.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        // 双重保险：即使预估不超，实际拿到接近上限的文本也按截断处理。
        if (payload.length >= TERMINAL_SAFE_BASE64_CHARS) {
            return RemoteFetchResult.Text(
                "读取 $target 失败：终端输出被截断（base64 长度 ${payload.length} 已到上限），" +
                    "写下去只会得到坏文件。请改用更小的文件，或先切片：\n" +
                    "terminal_exec: split -b 4000000 \"$target\" /tmp/chunk_\n" +
                    "然后逐个 fetch_remote /tmp/chunk_xx，本地拼接。",
            )
        }
        if (payload.length < 16) {
            return RemoteFetchResult.Text(
                "读取 $target 失败：终端未返回有效内容（可能输出被截断，或文件为空）。" +
                    "可改用 terminal_exec 自己处理（如 base64 分块）。",
            )
        }
        val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrElse { error ->
            throw IllegalStateException("解码失败（输出可能被终端截断）：${error.message.orEmpty().take(120)}", error)
        }
        val extension = target.substringAfterLast('.', "bin").lowercase()
        val file = files.newFile(extension)
        runCatching { file.writeBytes(bytes) }.getOrElse { error ->
            throw IllegalStateException("写入本地失败：${error.message.orEmpty().take(120)}", error)
        }
        val id = files.register(file, extension, McpFileStore.contentTypeOf(extension))
        return RemoteFetchResult.Saved(id, bytes.size.toLong(), target)
    }

    /** 大文件不硬试：给出真正可行的三条路（外部报告已验证方案 2 的路径结构）。 */
    private fun bigFileHint(path: String, size: Long, limit: Long): String = buildString {
        appendLine("$path 有 ${size / 1024 / 1024}MB，超过 fetch_remote 单次上限 ${limit / 1024 / 1024}MB。")
        appendLine("为什么不能直接取：本工具走终端通道读文件，终端是**文本协议**，")
        appendLine("大文件 base64 会翻倍且会超终端输出长度，硬试只会卡死。")
        appendLine()
        appendLine("可行做法（按推荐顺序）：")
        appendLine("1) 云端起临时 HTTP 服务，走平台已有的 api_serving 端口代理（报告实测路径结构支持）：")
        appendLine("   terminal_exec: cd ${path.substringBeforeLast('/', path)} && (nohup python3 -m http.server 9000 --bind 0.0.0.0 >/tmp/hs.log 2>&1 &)")
        appendLine("   下载地址：https://aistudio.baidu.com/<zone>/user/<uid>/<pid>/api_serving/9000/${path.substringAfterLast('/')}")
        appendLine("   前提：必须带**项目级 Cookie**（ide-proxy + user-<uid>-<pid>），账号级 Cookie 不够（会 302 到登录页）。")
        appendLine("   注意：非 8188 端口是否放行未验证，先在 App 里连终端拿到 Cookie 再试。")
        appendLine("2) 云端切片后分批取：")
        appendLine("   terminal_exec: split -b ${limit} \"$path\" /tmp/chunk_")
        appendLine("   然后逐个 fetch_remote /tmp/chunk_xx，本地拼接。")
        append("3) 若只是取回结果，建议改为让出图直接写 ComfyUI output，走 generate/job_status 那条已有通道。")
    }

    /**
     * 大文件自动代下（v0.3.27）。
     *
     * 分工：AI 不需要做任何事（连命令都不用发），本方法在云端起临时 HTTP 服务，
     * 手机自己带项目级 Cookie 下载，下完关掉服务、删除临时目录。
     *
     * 为什么不让 AI 自己拼 curl：它拿不到项目级 Cookie（ide-proxy / user-<uid>-<pid>），
     * 硬试必然 302 到登录页。而手机侧本来就有——这个能力不该外包给 AI。
     *
     * 失败时降级成 [bigFileHint]（给可执行的替代路径），不直接报错。
     */
    private suspend fun downloadViaApiServing(
        terminalHost: McpTerminalHost,
        path: String,
        size: Long,
        limit: Long,
    ): RemoteFetchResult {
        val ctx = runCatching { terminalHost.projectContextForDownload() }.getOrNull()
            ?: return RemoteFetchResult.Text(
                "$path 有 ${size / 1024 / 1024}MB，需要代下但**拿不到当前项目上下文**" +
                    "（未启动项目 / 未连上终端 / 无项目级 Cookie）。\n" + bigFileHint(path, size, limit),
            )

        // 端口选 9100：避开 ComfyUI 的 8188（很可能正占着），也避开常见冲突。
        val port = DOWNLOAD_SERVING_PORT
        val dir = path.substringBeforeLast('/', path).ifBlank { "/tmp" }
        val fileName = path.substringAfterLast('/')
        val url = ctx.baseUrl.trimEnd('/') + "/api_serving/$port/$fileName"

        // 起服务：绑定 0.0.0.0 才能被反代转发；--directory 限定目录，不暴露其他路径。
        // v0.3.30：路径与文件名都用单引号包，避免空格/特殊字符把命令拆坏。
        val dirQ = "'" + dir.replace("'", "'\\''") + "'"
        val nameQ = "'" + fileName.replace("'", "'\\''") + "'"
        val startCmd = "pkill -f 'http.server $port' 2>/dev/null; " +
            "(cd $dirQ && nohup python3 -m http.server $port --bind 0.0.0.0 >/tmp/mcp_dl_$port.log 2>&1 &); " +
            "sleep 2; curl -s -m 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:$port/$nameQ"
        val started = runCatching {
            terminalHost.exec(startCmd, terminal = null, timeoutSeconds = 25)
        }.getOrNull()
        if (started == null || !started.contains("200")) {
            return RemoteFetchResult.Text(
                "$path 有 ${size / 1024 / 1024}MB，代下失败：云端 HTTP 服务未能启动（端口 $port）。\n" +
                    "云端日志：/tmp/mcp_dl_$port.log\n" + bigFileHint(path, size, limit),
            )
        }

        // 手机侧下载（ComfyClient 的拦截器会自动带 Cookie）。
        val extension = fileName.substringAfterLast('.', "bin").lowercase()
        val target = files.newFile(extension)
        val ok = runCatching {
            target.outputStream().use { out -> client.downloadTo(url, out) }
        }.onFailure { error ->
            AppLogger.warn("代下 $path 失败（$url）", error)
        }.isSuccess
        // 无论成败都关掉云端服务：临时端口开着等于多一个对外暴露面。
        runCatching {
            terminalHost.exec(
                "pkill -f 'http.server $port' 2>/dev/null; rm -f /tmp/mcp_dl_$port.log",
                terminal = null,
                timeoutSeconds = 10,
            )
        }

        if (!ok || target.length() == 0L) {
            runCatching { target.delete() }
            return RemoteFetchResult.Text(
                "$path 有 ${size / 1024 / 1024}MB，代下失败：云端服务起来了（端口 $port 回 200），" +
                    "但手机侧下载没成功。最可能原因：平台没放行非 8188 端口（反代可能只白名单 8188）。\n" +
                    "可试：先停 ComfyUI 再把端口改成 8188，或改用切片分批。\n" + bigFileHint(path, size, limit),
            )
        }
        val id = files.register(target, extension, McpFileStore.contentTypeOf(extension))
        return RemoteFetchResult.Saved(id, target.length(), path)
    }

    override suspend fun jobStatus(jobId: String, waitSeconds: Int): GenerateOutcome {
        requireConnected()
        // 查询时不再长等：已经知道任务在跑了，给一次短窗口即可，避免把 job_status
        // 变成第二个会卡住的 generate。
        //
        // v0.3.2（文档 §4.1）：设计意图保留，但补了一个**显式选项**——真机实测 AI
        // 只能 5 秒一次轮询（一次跑图查询了 14 次），因为没别的办法等终态。
        // waitSeconds > 0 时同步等到终态/超时，一次调用拿结果；默认 0 保持原行为。
        return finishOrRunning(
            jobId,
            awaitMillis = waitSeconds.coerceIn(0, MAX_WAIT_SECONDS).toLong() * 1000,
            startedAt = System.currentTimeMillis(),
        )
    }

    /**
     * 列出本会话提交过的任务（v0.3.7）。
     *
     * 状态**实时查**而不是落盘：落盘的状态一过期就是错的，而 AI 拿错状态比拿不到
     * 状态更糟（它会据此决定不重提）。代价是每条要一次请求——记录不多，且只有
     * AI 主动调时才发生。
     */
    override suspend fun listMyJobs(state: String?, limit: Int?): String {
        requireConnected()
        val reader = submittedJobsReader
            ?: throw IllegalStateException("任务记录未启用（当前运行环境不支持）。")
        val records = runCatching { reader() }.getOrElse { error ->
            throw IllegalStateException("读取已提交任务失败：${error.message}", error)
        }
        // 全量拉记录后按时间倒序（新的在前），再查状态、再过筛选。
        val ordered = records.sortedByDescending { it.submittedAt }
        val wanted = state?.trim()?.lowercase()?.takeIf { it.isNotBlank() && it != "all" }
        val max = (limit ?: DEFAULT_JOB_LIST_LIMIT).coerceIn(1, McpJobListFormatter.MAX_SHOWN)

        val queue = runCatching { client.queue() }.getOrNull()
        // v0.3.21（真机报告 Bug7/Bug8）：改用**一次拉全量 history**，所有记录共享
        // 同一份快照，避免逐条单查时反代行为不稳定导致"这条 done、那条查不到"的
        // 自相矛盾。全量 history 对已完成任务的覆盖比单条查询稳定（反代更常缓存它）。
        val historyAll = runCatching { client.history() }.getOrNull()
        val entries = mutableListOf<McpJobListFormatter.Entry>()
        for (record in ordered) {
            if (entries.size >= max) break
            val entry = historyAll?.optJSONObject(record.jobId)
            val hasOutputs = entry?.optJSONObject("outputs")?.keys()?.hasNext() == true
            val verdict = McpJobState.resolve(record.jobId, queue, historyAll, hasOutputs)
            val label = verdict.phase.label
            if (wanted != null && !label.equals(wanted, ignoreCase = true)) continue
            entries += McpJobListFormatter.Entry(record = record, stateLabel = label, hasImages = hasOutputs)
        }
        return McpJobListFormatter.render(entries, totalShown = entries.size)
    }

    /**
     * 批量查询（v0.2.90）：一次拿多个任务的状态。
     *
     * AI 提交 8 个不同任务时，逐个查要 8 轮往返——与"提交/验收分离"直接冲突。
     * 队列只取一次，多个 job 共用同一份快照，既省请求也保证彼此一致。
     */
    override suspend fun jobStatusBatch(ids: List<String>, waitSeconds: Int): String {
        requireConnected()
        val targetIds = ids.take(MAX_BATCH_IDS).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        // v0.3.2：waitSeconds > 0 时先等一轮（任一任务到终态就继续），再统一查。
        // 批量等待的结束条件是「全部到终态或超时」——逐个等会放大耗时。
        if (waitSeconds > 0) {
            val deadline = System.currentTimeMillis() + waitSeconds.coerceIn(0, MAX_WAIT_SECONDS) * 1000L
            // v0.3.8（P1-2）：与 finishOrRunning 同一套早退——连续读不到就别干等。
            var unreachableStreak = 0
            var backoffIndex = 0
            while (System.currentTimeMillis() < deadline) {
                // v0.3.23：退避轮询（2s→4s→8s→10s）——批量等待场景更该退避，
                // 因为任务通常几十秒起，密集轮询只会烧平台请求。
                delay(mcpPollBackoffMillis(backoffIndex))
                backoffIndex++
                val queue = runCatching { client.queue() }.getOrNull()
                // v0.3.23：一次拉全量 history 共享快照，而不是对每个 id 各发一次
                // history(id)——N 个任务 × M 轮 = N×M 次请求，改为每轮 1 次全量。
                val historyAll = runCatching { client.history() }.getOrNull()
                unreachableStreak = if (queue == null && historyAll == null) unreachableStreak + 1 else 0
                if (unreachableStreak >= UNREACHABLE_TOLERANCE) {
                    return "读不到任何任务状态（连续 $unreachableStreak 次队列与历史都请求失败）：" +
                        "大概率是 ComfyUI 未启动或网络断了。先用 wait_for_comfy 确认环境再重试。"
                }
                val allSettled = targetIds.all { id ->
                    val entry = historyAll?.optJSONObject(id)
                    val hasOutputs = entry?.optJSONObject("outputs")?.keys()?.hasNext() == true
                    val verdict = McpJobState.resolve(id, queue, historyAll, hasOutputs)
                    verdict.phase == McpJobState.Phase.DONE || verdict.phase == McpJobState.Phase.FAILED
                }
                if (allSettled) break
            }
        }
        val queue = runCatching { client.queue() }.getOrNull()
        // v0.3.23：最终渲染也共享一份全量 history 快照，不再逐 id 单查。
        val historyAll = runCatching { client.history() }.getOrNull()
        val lines = mutableListOf<String>()
        var anySettled = false
        var anyUnsettled = false
        for (id in targetIds) {
            val entry = historyAll?.optJSONObject(id)
            val hasOutputs = entry?.optJSONObject("outputs")?.keys()?.hasNext() == true
            val verdict = McpJobState.resolve(id, queue, historyAll, hasOutputs)
            // 终态：done / failed。用来决定"整批都结束了"——凭 phase 判断，
            // 不去匹配展示文案（那种写法一改文案就静默失效）。
            when (verdict.phase) {
                McpJobState.Phase.DONE, McpJobState.Phase.FAILED -> anySettled = true
                else -> anyUnsettled = true
            }
            val detail = StringBuilder()
                .append(id.take(8))
                .append("  ")
                .append(verdict.phase.label)
            verdict.position?.let { detail.append(" · 位置 $it") }
            if (verdict.message.isNotBlank()) detail.append(" · ").append(verdict.message)
            lines += detail.toString()
        }
        if (lines.isEmpty()) return "没有可查询的 job_id。"
        // §4.2：真机实测 AI 会说"要停 GPU 跟我说一声"——**用户不问它就不停**，
        // 而 GPU 是按小时计费的。任务全部结束时主动提一句，比做看门狗便宜得多。
        val tail = if (anySettled && !anyUnsettled) {
            "\n全部任务已结束。如不再需要，可调 stop_gpu 停止计费（云端项目会一直按小时扣算力卡）。"
        } else {
            ""
        }
        return lines.joinToString("\n") + tail
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
                // v0.3.22（二轮报告）：原文说"请先打开 ComfyUIMobile 连接服务器"是误导——
                // ComfyUI 明明在跑，App 也连着，真实原因是 MCP 服务端还没建立连接句柄。
                // 正确动作是调 wait_for_comfy 预热（它会自动接入运行中的项目 api_serving）。
                "MCP 尚未连接到 ComfyUI：请先调 wait_for_comfy 预热" +
                    "（它会自动接入正在运行的项目 api_serving）。\n" +
                    "如果 wait_for_comfy 也报「未连接」，再回 App 确认 ComfyUI 已连接。",
            )
        }
    }

    private suspend fun finishOrRunning(
        promptId: String,
        awaitMillis: Long,
        startedAt: Long,
    ): GenerateOutcome {
        val deadline = startedAt + awaitMillis
        // v0.3.8（P1-2）：连续读不到状态的次数。
        //
        // 为什么用「两个信号源都为 null」而不是 phase==UNKNOWN：刚提交时任务
        // 还没进队列、history 里也没它，resolve 同样返回 UNKNOWN——那是**正常**的。
        // 真正的"环境没了"是 queue() 与 history() **两个请求都失败**。
        var unreachableStreak = 0
        // v0.3.23（性能）：轮询指数退避——2s→4s→8s→10s 封顶。
        //
        // 为什么必须做：旧实现固定 2 秒轮询，每轮发 2 个请求（queue+history），
        // 102 秒的任务要 51 轮 × 2 = 100+ 次平台请求——既是"慢"的根源，
        // 也是"短时间频繁访问被平台封 IP"的风险点。退避后 102 秒只约 15 轮，
        // 请求数砍 70%，而最坏只多等一次退避间隔（任务本身要 100 秒，多等 10 秒可忽略）。
        var backoffIndex = 0
        while (true) {
            // 统一判据：先队列后历史，两处信号源必须走同一个 resolve，
            // 否则"排队中"会被 history 缺失误判成运行中/失败。
            val queue = runCatching { client.queue() }.getOrNull()
            val history = runCatching { client.history(promptId) }.getOrNull()
            // 两个都读不到：ComfyUI 未启动 / 网络断了 / 反代挂了。
            // 原设计"读不到不能直接判失败"是对的（可能是抖动），所以只累计、不立即返回。
            unreachableStreak = McpJobState.nextUnreachableStreak(unreachableStreak, queue, history)
            if (unreachableStreak >= UNREACHABLE_TOLERANCE) {
                return GenerateOutcome.Unreachable(
                    promptId,
                    (System.currentTimeMillis() - startedAt) / 1000,
                    "连续 $unreachableStreak 次读不到任务状态（队列与历史都请求失败）",
                )
            }
            val entry = history?.optJSONObject(promptId)
            val hasOutputs = entry?.optJSONObject("outputs")?.keys()?.hasNext() == true
            val verdict = McpJobState.resolve(promptId, queue, history, hasOutputs)
            val elapsedSec = (System.currentTimeMillis() - startedAt) / 1000
            when (verdict.phase) {
                McpJobState.Phase.DONE -> {
                    val media = collectMedia(promptId, history ?: JSONObject())
                    if (media.isEmpty()) {
                        // v0.3.21：取不到图时**带上 /history 原始 JSON 的前缀**，
                        // 否则"success 但无图"这种反代 /view 不通的坑，对端完全无从排查。
                        val rawHistory = history?.optJSONObject(promptId)?.toString()
                        val snippet = rawHistory?.take(1200)?.let { "\nhistory 原始：$it" }.orEmpty()
                        return GenerateOutcome.Failed(
                            "任务 $promptId 已完成但没有取到图片（/view 可能被反代阻断，或 outputs 结构异常）。" +
                                snippet,
                        )
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
                McpJobState.Phase.RUNNING -> {
                    if (System.currentTimeMillis() >= deadline) {
                        return GenerateOutcome.Running(promptId, elapsedSec, verdict.message)
                    }
                }
                McpJobState.Phase.UNKNOWN -> {
                    // v0.3.21（真机报告 Bug7）：UNKNOWN 不能套 Running 返回——
                    // 否则 status=running 却带"查不到该任务"，自相矛盾。
                    // 查不到就是查不到，如实报 unknown。
                    if (System.currentTimeMillis() >= deadline) {
                        return GenerateOutcome.Unreachable(
                            promptId,
                            elapsedSec,
                            "队列与历史里都查不到该任务（可能已被清理或 job_id 有误）",
                        )
                    }
                }
            }
            // 退避：每轮间隔递增，封顶 POLL_MAX_INTERVAL_MILLIS。
            delay(mcpPollBackoffMillis(backoffIndex))
            backoffIndex++
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
            // v0.3.22（二轮 Bug2）：按 job+filename 复用已下载的文件——
            // 同一 job_id 反复 job_status 不再重新下载，URL 稳定不变号。
            val cacheKey = "job:$promptId:${item.filename}"
            val cached = files.lookupByKey(cacheKey)
            if (cached != null) {
                val cachedEntry = files.get(cached)
                if (cachedEntry != null) {
                    result += McpMedia(
                        filename = item.filename,
                        extension = extension,
                        contentType = McpFileStore.contentTypeOf(extension),
                        file = cachedEntry.file,
                    )
                    continue
                }
            }
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
        // v0.3.7：真取到图才标「已取图」——list_my_jobs 靠它把"还没取的"挑出来。
        // 放在这里而不是调用点：collectMedia 是**所有**取图路径的唯一出口。
        if (result.isNotEmpty()) {
            submittedJobFetched?.let { mark ->
                runCatching { mark(promptId) }
                    .onFailure { AppLogger.warn("标记任务已取图失败：$promptId", it) }
            }
        }
        return result
    }

    private fun errorSummary(job: JSONObject?): String {
        val status = job?.optJSONObject("status") ?: return "无输出"
        val statusStr = status.optString("status_str", "无输出")
        // v0.3.21：status_str 为 success/正常终态但 outputs 为空时，
        // 把"success"当失败原因输出是误导（真机报告 Bug1）——改为明确说
        // "任务成功但 outputs 为空"，并提示可能是反代截断了 /history 的 outputs。
        if (statusStr.equals("success", ignoreCase = true)) {
            return "任务在 ComfyUI 侧已成功（status=success），但 /history 未返回 outputs。" +
                "通常是反代对 /history/{id} 做了截断或缓存，图片可能已生成但无法经 MCP 取回。"
        }
        val messages = status.optJSONArray("messages") ?: return statusStr
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
        return summarized.ifBlank { statusStr }
    }

    private companion object {
        /**
         * 轮询起步间隔（v0.3.23 前固定 2 秒，现在只作退避序列的起点）。
         * 首次轮询仍要快——任务可能秒级完成（缓存命中 / 短任务）。
         */
        const val POLL_INTERVAL_MILLIS = 2_000L

        /**
         * 轮询退避封顶（v0.3.23）：10 秒。
         *
         * 退避序列 2s→4s→8s→10s→10s…。102 秒任务约 15 轮（旧 51 轮），
         * 请求数砍 70%，最坏多等 10 秒。10 秒是"不封 IP"与"不牺牲快"的平衡点。
         */
        const val POLL_MAX_INTERVAL_MILLIS = 10_000L

        /** 单次取回的最大图片数（批量出图时不让内存爆掉）。 */
        const val MAX_IMAGES = 8

        /** 批量 job_status 一次最多查多少个 id（防超长参数把请求撑爆）。 */
        const val MAX_BATCH_IDS = 16

        /**
         * job_status 可同步等待的上限（秒，v0.3.2）。单图 20~60s、批量更久，
         * 300s 足够覆盖绝大多数场景；再长就该让 AI 用 terminal_read 去看日志了。
         */
        const val MAX_WAIT_SECONDS = 300

        /** 批量等待时的轮询间隔（v0.3.23 起用退避，此常量仅作起步间隔）。 */
        const val WAIT_POLL_MILLIS = 2_000L

        /** describe_workflow 一次最多列多少字段（太长会挤爆模型上下文）。 */
        const val MAX_DESCRIBE_FIELDS = 40

        /** list_my_jobs 的默认返回条数（上限见 McpJobListFormatter.MAX_SHOWN）。 */
        const val DEFAULT_JOB_LIST_LIMIT = 20

        /**
         * 连续多少次「队列与历史都读不到」就早退（v0.3.8，P1-2）。
         *
         * 3 次 ≈ 轮询间隔 × 3。要略大于单次网络抖动的持续时间，又不能大到
         * 白等很久——这是个折中，不是精确值。
         */
        const val UNREACHABLE_TOLERANCE = 3

        /** 本 App 的 MCP 通道提交任务时写到 extra_data 的来源标记。 */
        const val ORIGIN_MCP = "mcp"

        /** 内联工作流在 extra_data 里记的路径标识（没有真实文件路径）。 */
        const val INLINE_WORKFLOW_PATH = "(inline)"

        /** wait_for_comfy 的探测间隔：2 秒足够密，又不会把服务器问烦。 */
        const val WAIT_FOR_COMFY_POLL_MILLIS = 2_000L

        /**
         * fetch_remote 单次取回的文件上限（v0.3.26）：8 MB。
         *
         * 为什么是 8MB：走终端通道 base64 传输，数据量翻倍；而终端输出本身
         * 还有长度限制。8MB 原文件 ≈ 16MB base64 文本，已是能稳定取回的边界。
         * 更大的文件给指引（云端起 HTTP + api_serving 代理，或切片分批）。
         */
        const val FETCH_REMOTE_MAX_BYTES = 8L * 1024 * 1024

        /**
         * 大文件代下时云端临时 HTTP 服务的端口（v0.3.27）。
         *
         * 选 9100 而不是 8188：后者是 ComfyUI 默认端口，很可能正占着。
         * 反代是否放行非 8188 端口未验证——代下失败时返回里会提示改成 8188。
         */
        const val DOWNLOAD_SERVING_PORT = 9100

        /**
         * 走 base64 回读的字符上限（v0.3.29）。
         *
         * 必须小于 TerminalShell.MAX_OUTPUT_CHARS（16000），留出命令回显与
         * 截断提示的余量。超过这个量就改走代下——base64 走终端超限必被截断，
         * 而截断后的数据解出来是**坏文件**，却会报「成功」。
         *
         * 8000 字符 ≈ 6KB 原文件，只适合极小的文本/配置文件。
         */
        const val TERMINAL_SAFE_BASE64_CHARS = 8_000L
    }
}

/**
 * 轮询退避间隔（v0.3.23）：2s、4s、8s、10s、10s…
 *
 * 顶层函数便于单测；第 0 次返回起步间隔，之后每次翻倍，封顶 10 秒。
 * 与 [ComfyMcpHost] 内的轮询循环共用同一套序列。
 */
internal fun mcpPollBackoffMillis(index: Int): Long {
    if (index < 0) return 2_000L
    val doubled = 2_000L shl index.coerceAtMost(3)
    return doubled.coerceAtMost(10_000L)
}
