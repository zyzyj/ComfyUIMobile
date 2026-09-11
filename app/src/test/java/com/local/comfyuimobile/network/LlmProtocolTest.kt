package com.local.comfyuimobile.network

import com.local.comfyuimobile.model.LlmConfig
import com.local.comfyuimobile.model.LlmPreset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmProtocolTest {

    // ===== 端点规范化 =====

    @Test
    fun `裸域名自动补 v1`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            LlmProtocol.chatEndpoint("https://api.openai.com"),
        )
    }

    @Test
    fun `已带 v1 不再重复补`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            LlmProtocol.chatEndpoint("https://api.deepseek.com/v1"),
        )
    }

    @Test
    fun `带尾斜杠也能补对`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            LlmProtocol.chatEndpoint("https://api.deepseek.com/v1/"),
        )
    }

    @Test
    fun `用户粘了完整地址则原样保留`() {
        val full = "https://my-gateway.example.com/v1/chat/completions"
        assertEquals(full, LlmProtocol.chatEndpoint(full))
    }

    @Test
    fun `自建网关带子路径也能补`() {
        assertEquals(
            "https://x.example.com/api/v1/chat/completions",
            LlmProtocol.chatEndpoint("https://x.example.com/api"),
        )
    }

    @Test
    fun `空地址返回空串而不是拼出半个 URL`() {
        assertEquals("", LlmProtocol.chatEndpoint("   "))
    }

    // ===== 请求体 =====

    @Test
    fun `请求体带 system 与 user 两条消息`() {
        val body = JSONObject(
            LlmProtocol.buildRequestBody(
                LlmConfig(baseUrl = "https://x", model = "gpt-4o-mini"),
                "SYS",
                "USER",
            ),
        )
        assertEquals("gpt-4o-mini", body.getString("model"))
        assertEquals(false, body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("SYS", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("USER", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun `system 为空时只发一条消息`() {
        val body = JSONObject(
            LlmProtocol.buildRequestBody(
                LlmConfig(baseUrl = "https://x", model = "m"),
                "",
                "USER",
            ),
        )
        assertEquals(1, body.getJSONArray("messages").length())
    }

    @Test
    fun `温度超范围被夹住`() {
        val body = JSONObject(
            LlmProtocol.buildRequestBody(
                LlmConfig(baseUrl = "https://x", model = "m", temperature = 99f),
                "",
                "U",
            ),
        )
        assertEquals(2.0, body.getDouble("temperature"), 0.001)
    }

    @Test
    fun `apiKey 为空时不发 Authorization`() {
        assertEquals(null, LlmProtocol.authHeader(LlmConfig(baseUrl = "https://x", model = "m")))
        assertEquals(null, LlmProtocol.authHeader(LlmConfig(baseUrl = "https://x", model = "m", apiKey = "  ")))
        assertEquals(
            "Bearer sk-abc",
            LlmProtocol.authHeader(LlmConfig(baseUrl = "https://x", model = "m", apiKey = " sk-abc ")),
        )
    }

    // ===== 响应解析 =====

    @Test
    fun `标准 choices message content`() {
        val raw = """{"choices":[{"message":{"role":"assistant","content":"1girl, masterpiece"}}]}"""
        assertEquals("1girl, masterpiece", LlmProtocol.parseContent(raw))
    }

    @Test
    fun `推理模型把正文放在 reasoning_content`() {
        val raw = """{"choices":[{"message":{"content":"","reasoning_content":"1girl, blue eyes"}}]}"""
        assertEquals("1girl, blue eyes", LlmProtocol.parseContent(raw))
    }

    @Test
    fun `兼容 completions 风格的 text 字段`() {
        val raw = """{"choices":[{"text":"a cat"}]}"""
        assertEquals("a cat", LlmProtocol.parseContent(raw))
    }

    @Test
    fun `模型套了代码围栏会被剥掉`() {
        val raw = """{"choices":[{"message":{"content":"```\n1girl, smile\n```"}}]}"""
        assertEquals("1girl, smile", LlmProtocol.parseContent(raw))
    }

    @Test
    fun `无围栏标记的纯文本原样返回`() {
        val raw = """{"choices":[{"message":{"content":"  a, b  "}}]}"""
        assertEquals("a, b", LlmProtocol.parseContent(raw))
    }

    @Test
    fun `服务端返回 error 字段时报出来`() {
        val raw = """{"error":{"message":"model not found"}}"""
        try {
            LlmProtocol.parseContent(raw)
            throw AssertionError("应当抛异常")
        } catch (error: LlmException) {
            assertTrue(error.message!!.contains("model not found"))
        }
    }

    @Test
    fun `非 JSON 响应给出可读提示`() {
        try {
            LlmProtocol.parseContent("<html>502 Bad Gateway</html>")
            throw AssertionError("应当抛异常")
        } catch (error: LlmException) {
            assertTrue(error.message!!.contains("非 JSON"))
        }
    }

    // ===== 错误描述 =====

    @Test
    fun `401 提示密钥问题`() {
        val text = LlmProtocol.describeHttpError(401, """{"error":{"message":"invalid api key"}}""")
        assertTrue(text.contains("HTTP 401"))
        assertTrue(text.contains("invalid api key"))
        assertTrue(text.contains("API Key"))
    }

    @Test
    fun `404 提示地址可能少了 v1`() {
        assertTrue(LlmProtocol.describeHttpError(404, "").contains("/v1"))
    }

    @Test
    fun `非 JSON 错误体截取前一段而不是整页 HTML`() {
        val long = "<html>" + "x".repeat(500) + "</html>"
        assertTrue(LlmProtocol.describeHttpError(500, long).length < 220)
    }

    // ===== 配置可用性 =====

    @Test
    fun `地址与模型名齐全才可用`() {
        assertTrue(LlmConfig().isConfigured().not())
        assertTrue(LlmConfig(baseUrl = "https://x").isConfigured().not())
        assertTrue(LlmConfig(baseUrl = "https://x", model = "m").isConfigured())
    }

    @Test
    fun `未知 preset id 回落到通用`() {
        assertEquals(LlmPreset.GENERAL, LlmPreset.fromId("不存在的"))
        assertEquals(LlmPreset.GENERAL, LlmPreset.fromId(null))
        assertEquals(LlmPreset.ANIMA, LlmPreset.fromId("anima"))
    }

    @Test
    fun `Anima 预设的 system prompt 含固定质量前缀且默认 safe`() {
        val prompt = LlmPrompts.systemPrompt(LlmPreset.ANIMA, isNegative = false)
        assertTrue(prompt.contains("masterpiece, very aesthetic"))
        assertTrue(prompt.contains("year 2025"))
        assertTrue(prompt.contains("safe"))
    }

    @Test
    fun `负向字段会改写 system prompt 而不是沿用正向规则`() {
        val negative = LlmPrompts.systemPrompt(LlmPreset.GENERAL, isNegative = true)
        assertTrue(negative.contains("NEGATIVE"))
        assertTrue(negative.contains("watermark"))
    }

    @Test
    fun `追加模式的 user 消息带上现有提示词`() {
        val text = LlmPrompts.userPrompt(
            com.local.comfyuimobile.model.AiAssistMode.APPEND,
            "1girl",
            "blue dress",
        )
        assertTrue(text.contains("1girl"))
        assertTrue(text.contains("blue dress"))
        assertTrue(text.contains("ONLY"))
    }

    @Test
    fun `润色模式不带额外要求时只给原文`() {
        val text = LlmPrompts.userPrompt(
            com.local.comfyuimobile.model.AiAssistMode.POLISH,
            "1girl, smile",
            "",
        )
        assertTrue(text.contains("1girl, smile"))
        assertTrue(text.contains("Extra requirements").not())
    }
}
