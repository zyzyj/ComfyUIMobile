package com.local.comfyuimobile.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 协议层单测（v0.2.85）。
 *
 * 重点是**客户端源码里确认过的形态**：通知回 202、`Mcp-Session-Id` 可选、工具名长度上限。
 * 这些一旦写偏，握手就过不去，而现象只是"连不上"，排查成本很高。
 */
class McpProtocolTest {

    // ===== 工具名约束 =====

    @Test
    fun toolNameFollowsAiCodeNamespaceRule() {
        assertEquals("mcp__comfy__list_models", McpProtocol.toolName("comfy", "list_models"))
    }

    @Test
    fun toolNameRejectsIllegalCharacters() {
        // AiCode 的 NAME_REGEX 只允许 [a-zA-Z0-9_-]：点号、空格、中文都会被它清洗成
        // 下划线。这里在**本侧**就拒掉，避免双方对不上的"隐式改名"。
        assertFalse(McpProtocol.isToolNameValid("comfy", "list.models"))
        assertFalse(McpProtocol.isToolNameValid("comfy", "列表"))
        assertFalse(McpProtocol.isToolNameValid("comfy", "list models"))
        assertTrue(McpProtocol.isToolNameValid("comfy", "job_status"))
    }

    @Test
    fun toolNameRejectsOverLongName() {
        // `mcp__comfy__` 占 12 字符，56 字符的工具名恰好到 64 上限；再多一个就超。
        val ok = "x".repeat(McpProtocol.MAX_TOOL_NAME_LENGTH - "mcp__comfy__".length)
        assertTrue(McpProtocol.isToolNameValid("comfy", ok))
        assertFalse(McpProtocol.isToolNameValid("comfy", ok + "x"))
    }

    @Test
    fun allRegisteredToolsRespectNamingRule() {
        for (tool in McpToolRegistry.TOOL_NAMES) {
            assertTrue("工具名不合法：$tool", McpProtocol.isToolNameValid(McpProtocol.SERVER_NAME, tool))
        }
    }

    // ===== 请求解析 =====

    @Test
    fun parsesRequestWithId() {
        val rpc = McpProtocol.parseRequest("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertNotNull(rpc)
        assertEquals("1", rpc!!.id)
        assertEquals("tools/list", rpc.method)
    }

    @Test
    fun parsesNotificationWithoutId() {
        val rpc = McpProtocol.parseRequest("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertNotNull(rpc)
        assertNull("通知没有 id，服务端据此回 202", rpc!!.id)
    }

    @Test
    fun treatsNullIdAsNotification() {
        val rpc = McpProtocol.parseRequest("""{"jsonrpc":"2.0","id":null,"method":"notifications/initialized"}""")
        assertNotNull(rpc)
        assertNull(rpc!!.id)
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(McpProtocol.parseRequest("not json"))
        assertNull(McpProtocol.parseRequest(""))
    }

    @Test
    fun nonStringIdIsStringified() {
        // 实测 AiCode 用自增 Long 作为 id，不是字符串——解析时必须能接住。
        val rpc = McpProtocol.parseRequest("""{"jsonrpc":"2.0","id":42,"method":"initialize"}""")
        assertEquals("42", rpc!!.id)
    }

    // ===== 响应构造 =====

    @Test
    fun initializeResultDeclaresToolsCapabilityOnly() {
        val result = McpProtocol.initializeResult()
        assertEquals("2025-06-18", result.getString("protocolVersion"))
        assertTrue(result.getJSONObject("capabilities").has("tools"))
        // resources / prompts 客户端不支持（源码级确证），不该出现在能力声明里。
        assertFalse(result.getJSONObject("capabilities").has("resources"))
        assertFalse(result.getJSONObject("capabilities").has("prompts"))
    }

    @Test
    fun textResultUsesTextContentBlock() {
        val result = McpProtocol.textResult("hello")
        val content = result.getJSONArray("content").getJSONObject(0)
        assertEquals("text", content.getString("type"))
        assertEquals("hello", content.getString("text"))
        assertFalse(result.getBoolean("isError"))
    }

    @Test
    fun errorResultKeepsJsonRpcShape() {
        val error = McpProtocol.error("7", McpProtocol.ErrorCode.METHOD_NOT_FOUND, "unknown")
        assertEquals("2.0", error.getString("jsonrpc"))
        assertEquals("7", error.getString("id"))
        assertEquals(-32601, error.getJSONObject("error").getInt("code"))
    }

    @Test
    fun successWithNullIdIsStillValidJson() {
        // 通知路径下 id 是 JSON null；序列化不能抛（JSONObject.NULL 的正确用法）。
        val body = McpProtocol.success(null, JSONObject().put("ok", true))
        assertTrue(JSONObject(body.toString()).isNull("id"))
    }
}
