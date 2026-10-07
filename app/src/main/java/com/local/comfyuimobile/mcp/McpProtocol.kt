package com.local.comfyuimobile.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 的 JSON-RPC 编解码与工具名规则（v0.2.85）。
 *
 * 协议版本与响应形态来自对 AiCode 客户端的源码核对与实测（规划书 §3、§11）：
 *  - 协议版本 `2025-06-18`；
 *  - 通知回 202 空体；
 *  - 响应回纯 `application/json` 即可，不必实现 SSE；
 *  - `Mcp-Session-Id` 可选——这里回显客户端带来的值，但**服务端不维护会话状态**。
 *
 * 纯函数，可脱离 Android 单测。
 */
internal object McpProtocol {

    const val PROTOCOL_VERSION = "2025-06-18"
    const val SERVER_NAME = "comfy"
    const val SERVER_VERSION = "1.0.0"

    /** 工具名总长上限（对齐 AiCode 的 `McpTool`：`mcp__{server}__{tool}`）。 */
    const val MAX_TOOL_NAME_LENGTH = 64

    /** 服务器名合法字符集（AiCode `McpServerConfig.NAME_REGEX`）。 */
    private val NAME_REGEX = Regex("[a-zA-Z0-9_-]+")

    /**
     * 拼最终工具名并校验长度。
     *
     * AiCode 侧超长会做 sha1 截断——工具名会变成乱码，且它展示给你看的、和你要在
     * 文档里写的名字对不上。与其依赖对方的兜底，不如在**本侧**就把它做成不可能发生：
     * 超长时直接报错，开发期立刻发现，而不是等上线后拿到一串哈希。
     */
    fun toolName(server: String, tool: String): String {
        require(NAME_REGEX.matches(server)) { "服务器名只允许字母、数字、下划线、连字符：$server" }
        require(NAME_REGEX.matches(tool)) { "工具名只允许字母、数字、下划线、连字符：$tool" }
        val full = "mcp__${server}__${tool}"
        require(full.length <= MAX_TOOL_NAME_LENGTH) {
            "工具名过长（${full.length} > $MAX_TOOL_NAME_LENGTH）：$full"
        }
        return full
    }

    /** 校验一个工具描述符的命名合法（供单测与启动期自检用）。 */
    fun isToolNameValid(server: String, tool: String): Boolean = runCatching {
        toolName(server, tool)
    }.isSuccess

    // ===== 请求解析 =====

    data class RpcRequest(
        /** 空表示这是**通知**（无 id），不需要响应体。 */
        val id: String?,
        val method: String,
        val params: JSONObject,
    )

    /** 解析失败的请求：返回 null，调用方回 JSON-RPC 的 -32700。 */
    fun parseRequest(body: String): RpcRequest? = runCatching {
        val root = JSONObject(body)
        val id = when {
            !root.has("id") || root.isNull("id") -> null
            else -> root.get("id").let { if (it is String) it else it.toString() }
        }
        RpcRequest(
            id = id,
            method = root.optString("method"),
            params = root.optJSONObject("params") ?: JSONObject(),
        )
    }.getOrNull()

    // ===== 响应构造 =====

    fun success(id: String?, result: JSONObject): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", result)

    fun error(id: String?, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    /** `initialize` 结果。只声明 tools 能力——resources / prompts 客户端不支持（源码级确证）。 */
    fun initializeResult(instructions: String? = null): JSONObject = JSONObject()
        .put("protocolVersion", PROTOCOL_VERSION)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put(
            "serverInfo",
            JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION),
        )
        .apply { if (!instructions.isNullOrBlank()) put("instructions", instructions) }

    /** 工具描述符 → `tools/list` 的一项。 */
    fun toolDescriptor(name: String, description: String, inputSchema: JSONObject): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put("inputSchema", inputSchema)

    /**
     * `tools/call` 的文本结果。
     *
     * 只回 text content：AiCode 的 `flattenContent` 会把 `image` 块压成字面量
     * `[image content]`（源码确证），所以图片走 `/files/{id}` 端点、这里只给 URL。
     */
    fun textResult(text: String, isError: Boolean = false): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", isError)

    /** JSON-RPC 标准错误码。 */
    object ErrorCode {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}
