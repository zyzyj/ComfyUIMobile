package com.local.comfyuimobile.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 工具层（v0.2.85）。
 *
 * 分两层，目的是让**分派逻辑**可脱离 Android 单测：
 *  - [McpToolHost]：真正干活的一侧（查 ComfyUI、出图），由 App 侧实现；
 *  - [McpToolRegistry]：参数校验、结果序列化、方法路由——纯逻辑。
 *
 * 工具清单（规划书 §5）。刻意**不含 `terminal`**：MCP 的 tool call 由 AiCode 自主发起、
 * 不经过本 App 的界面，而现有安全模型（[com.local.comfyuimobile.model.TerminalCommandSafety]
 * 的三档 + 人工确认）正建立在"用户点确认"这个前提上。把"提议 + 人工确认"改成
 * "server 自行拒绝"是一次独立的安全模型改造，不该和首次打通混在一批改动里。
 */
internal interface McpToolHost {

    /** 列出 ComfyUI 认识的模型文件名（走 `/object_info`，非扫盘）。 */
    suspend fun listModels(type: String?): String

    /** 列出可用工作流。 */
    suspend fun listWorkflows(): String

    /** 提交一次生成；最多同步等 [awaitMillis]，超时返回运行中。 */
    suspend fun generate(request: GenerateRequest, awaitMillis: Long): GenerateOutcome

    /** 查一次已有任务的状态。 */
    suspend fun jobStatus(jobId: String): GenerateOutcome

    /** 批量查询多个任务的状态（v0.2.90）：返回给模型的文本，不走 GenerateOutcome。 */
    suspend fun jobStatusBatch(ids: List<String>): String
}

internal data class GenerateRequest(
    val prompt: String,
    val negative: String,
    val workflow: String?,
    val checkpoint: String?,
    val lora: String?,
    val count: Int,
)

internal sealed interface GenerateOutcome {
    /** 完成，附带可下载的图片。 */
    data class Done(val jobId: String, val media: List<McpMedia>) : GenerateOutcome

    /** 仍在跑：告诉对端 job_id 与已等时长，让它自行决定是否轮询。 */
    data class Running(
        val jobId: String,
        val elapsedSec: Long,
        val message: String,
        /** 队内位置（1 起）；排队时才有意义。 */
        val position: Int? = null,
    ) : GenerateOutcome

    /**
     * 排队中（v0.2.90）：与 running 分开——排队时模型不该反复查询，
     * 而运行中已经开始了，值得继续等。
     */
    data class Queued(
        val jobId: String,
        val elapsedSec: Long,
        val position: Int?,
        val message: String,
    ) : GenerateOutcome

    data class Failed(val message: String) : GenerateOutcome
}

internal data class McpMedia(
    val filename: String,
    val extension: String,
    val contentType: String,
    /** 已落盘的文件。**不持有字节**——内容经 `/files/{id}` 流式发送。 */
    val file: java.io.File,
)

/**
 * 工具分派器。
 *
 * @param files 与 [McpServer] 共用同一实例——出图后在这里登记、由 server 的 `/files` 取。
 * @param fileUrlBase 生成可下载 URL 的前缀，形如 `http://127.0.0.1:8765/files/`。
 *        **不用硬编码端口**：端口可配置，且 port=0 时由系统分配（单测用）。
 */
internal class McpToolRegistry(
    private val host: McpToolHost,
    private val files: McpFileStore,
    private val fileUrlBase: () -> String,
) {

    /** `generate` 同步等待上限：覆盖多数单图场景，重任务走 job_status（规划书 §5.2）。 */
    var generateAwaitMillis: Long = DEFAULT_GENERATE_AWAIT_MILLIS

    /**
     * 处理一个 JSON-RPC 请求。返回 null 表示这是**通知**（无 id），调用方回 202 空体。
     */
    suspend fun dispatch(method: String, params: JSONObject, id: String?): JSONObject? {
        if (id == null && method.startsWith("notifications/")) return null
        return when (method) {
            "initialize" -> McpProtocol.success(id, McpProtocol.initializeResult())
            "tools/list" -> McpProtocol.success(id, JSONObject().put("tools", toolList()))
            "tools/call" -> handleToolCall(id, params)
            "ping" -> McpProtocol.success(id, JSONObject())
            else -> McpProtocol.error(id, McpProtocol.ErrorCode.METHOD_NOT_FOUND, "未知方法：$method")
        }
    }

    private suspend fun handleToolCall(id: String?, params: JSONObject): JSONObject {
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val result = try {
            invoke(name, args)
        } catch (error: Exception) {
            // 工具执行失败按 MCP 约定回 isError=true 的**正常结果**（而非 JSON-RPC error），
            // 这样模型能看到失败原因并自行调整，而不是整轮对话中断。
            return McpProtocol.success(
                id,
                McpProtocol.textResult("工具 $name 执行失败：${error.message}", isError = true),
            )
        }
        return McpProtocol.success(id, McpProtocol.textResult(result.text, isError = result.isError))
    }

    /**
     * 工具结果。
     *
     * [isError] 必须能区分"业务上失败"与"成功"：出图失败（工作流格式不对、服务器拒绝）
     * 对模型来说就是错误，它需要据此换策略；而运行中/完成都算正常结果。
     */
    internal data class ToolResult(val text: String, val isError: Boolean = false)

    private suspend fun invoke(name: String, args: JSONObject): ToolResult = when (name) {
        TOOL_LIST_MODELS -> ToolResult(host.listModels(args.optString("type").takeIf { it.isNotBlank() }))
        TOOL_LIST_WORKFLOWS -> ToolResult(host.listWorkflows())
        TOOL_GENERATE -> renderGenerate(host.generate(parseGenerate(args), generateAwaitMillis))
        TOOL_JOB_STATUS -> {
            val ids = parseJobIds(args)
            // 提交多个任务后逐个查要好多轮往返——支持一次传多个 id。
            if (ids.size > 1) ToolResult(host.jobStatusBatch(ids))
            else renderGenerate(host.jobStatus(ids.first()))
        }
        else -> throw IllegalArgumentException("未知工具：$name")
    }

    /** job_id 支持单个字符串或数组。缺参数时明确报错，不要静默当空。 */
    private fun parseJobIds(args: JSONObject): List<String> {
        val single = args.optString("job_id").trim()
        val array = args.optJSONArray("job_ids")
        val ids = buildList {
            if (single.isNotBlank()) add(single)
            if (array != null) {
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }.distinct().take(MAX_BATCH_IDS)
        if (ids.isEmpty()) throw IllegalArgumentException("缺少 job_id（或 job_ids）")
        return ids
    }

    private fun parseGenerate(args: JSONObject): GenerateRequest {
        val prompt = args.optString("prompt").trim()
        if (prompt.isBlank()) throw IllegalArgumentException("缺少 prompt")
        // count 夹在 1..8：批量出图会让同步等待成倍拉长，而上限过低又没意义。
        val count = args.optInt("count", 1).coerceIn(1, 8)
        return GenerateRequest(
            prompt = prompt,
            negative = args.optString("negative").trim(),
            workflow = args.optString("workflow").trim().takeIf { it.isNotBlank() },
            checkpoint = args.optString("checkpoint").trim().takeIf { it.isNotBlank() },
            lora = args.optString("lora").trim().takeIf { it.isNotBlank() },
            count = count,
        )
    }

    /** 把结果渲染成给模型看的文本。图片只给 URL——AiCode 不渲染 image content block。 */
    private fun renderGenerate(outcome: GenerateOutcome): ToolResult = when (outcome) {
        is GenerateOutcome.Failed -> ToolResult("生成失败：${outcome.message}", isError = true)
        is GenerateOutcome.Queued -> ToolResult(
            JSONObject()
                .put("status", "queued")
                .put("job_id", outcome.jobId)
                .put("elapsed_sec", outcome.elapsedSec)
                .apply { outcome.position?.let { put("position", it) } }
                .put("message", outcome.message)
                .put("hint", "排队中，过几秒再用 job_status 查；排队不影响你先做别的")
                .toString(),
        )
        is GenerateOutcome.Running -> ToolResult(
            JSONObject()
                .put("status", "running")
                .put("job_id", outcome.jobId)
                .put("elapsed_sec", outcome.elapsedSec)
                .put("message", outcome.message)
                .put("hint", "用 job_status 轮询，或直接在 App 里看进度")
                .toString(),
        )
        is GenerateOutcome.Done -> {
            val arr = JSONArray()
            for (media in outcome.media) {
                val fileId = files.register(media.file, media.extension, media.contentType)
                arr.put(
                    JSONObject()
                        .put("filename", media.filename)
                        .put("url", fileUrlBase() + fileId),
                )
            }
            ToolResult(
                JSONObject()
                    .put("status", "done")
                    .put("job_id", outcome.jobId)
                    .put("images", arr)
                    .put("hint", "用 curl 下载 url 到容器本地后用 viewImage 查看")
                    .toString(),
            )
        }
    }

    private fun toolList(): JSONArray = JSONArray().apply {
        put(
            McpProtocol.toolDescriptor(
                TOOL_LIST_MODELS,
                "列出当前 ComfyUI 服务器可用的模型文件名（checkpoint / LoRA）。" +
                    "数据来自 ComfyUI 的 /object_info，是服务器自己认识的权威清单。",
                schema(
                    JSONObject().put(
                        "type",
                        JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray().put("checkpoint").put("lora"))
                            .put("description", "限定类型；省略则全部列出"),
                    ),
                ),
            ),
        )
        put(
            McpProtocol.toolDescriptor(
                TOOL_LIST_WORKFLOWS,
                "列出服务器上可用的 ComfyUI 工作流（名称、路径，以及是否为 API 格式）。" +
                    "generate 只能执行标记为 [可直用] 的工作流。",
                schema(JSONObject()),
            ),
        )
        put(
            McpProtocol.toolDescriptor(
                TOOL_GENERATE,
                "用指定工作流出图。会同步等待直到完成或超时；超时返回 running 与 job_id，" +
                    "此时改用 job_status 查询。返回的图片以 URL 给出，需自行下载后查看。" +
                    "仅支持 API 格式工作流（ComfyUI 的 Export (API) 导出）。",
                schema(
                    JSONObject()
                        .put("prompt", JSONObject().put("type", "string").put("description", "正向提示词"))
                        .put("negative", JSONObject().put("type", "string").put("description", "负向提示词（可选）"))
                        .put("workflow", JSONObject().put("type", "string").put("description", "工作流路径（可选，见 list_workflows；缺省用 App 当前打开的工作流，且必须是 API 格式）"))
                        .put("checkpoint", JSONObject().put("type", "string").put("description", "模型文件名（可选）"))
                        .put("lora", JSONObject().put("type", "string").put("description", "LoRA 文件名（可选）"))
                        .put("count", JSONObject().put("type", "integer").put("description", "出图张数，1-8，默认 1")),
                    required = JSONArray().put("prompt"),
                ),
            ),
        )
        put(
            McpProtocol.toolDescriptor(
                TOOL_JOB_STATUS,
                "查询一次生成任务的状态，返回 queued / running / done / failed。" +
                    "可传 job_id（单个）或 job_ids（数组，最多 16 个）一次查多个；完成时给图片 URL。",
                schema(
                    JSONObject()
                        .put("job_id", JSONObject().put("type", "string").put("description", "generate 返回的 job_id"))
                        .put(
                            "job_ids",
                            JSONObject()
                                .put("type", "array")
                                .put("description", "一次查多个任务的 id")
                                .put("items", JSONObject().put("type", "string")),
                        ),
                ),
            ),
        )
    }

    private fun schema(properties: JSONObject, required: JSONArray = JSONArray()): JSONObject =
        JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", required)

    internal companion object {
        const val TOOL_LIST_MODELS = "list_models"
        const val TOOL_LIST_WORKFLOWS = "list_workflows"
        const val TOOL_GENERATE = "generate"
        const val TOOL_JOB_STATUS = "job_status"

        /**
         * 120 秒。60 秒太短（SDXL 单图常见 20-60 秒，带高清修复就超），
         * 600 秒太长（无进度通知，界面会静默干等）——见规划书 §5.2。
         */
        const val DEFAULT_GENERATE_AWAIT_MILLIS = 120_000L

        /** 批量 job_status 一次最多查多少个 id（与 host 侧同一上限）。 */
        const val MAX_BATCH_IDS = 16

        /** 所有工具名（供启动期自检命名约束）。 */
        val TOOL_NAMES = listOf(TOOL_LIST_MODELS, TOOL_LIST_WORKFLOWS, TOOL_GENERATE, TOOL_JOB_STATUS)
    }
}
