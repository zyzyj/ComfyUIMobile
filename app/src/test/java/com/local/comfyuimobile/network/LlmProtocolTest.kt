package com.local.comfyuimobile.network

import com.local.comfyuimobile.model.LlmConfig
import com.local.comfyuimobile.model.PromptPreset
import com.local.comfyuimobile.model.PromptPresets
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

    // ===== 模型列表（v0.2.56） =====

    @Test
    fun `models 端点与 chat 端点同一套归一规则`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            LlmProtocol.modelsEndpoint("https://api.openai.com"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/models",
            LlmProtocol.modelsEndpoint("https://api.deepseek.com/v1"),
        )
        // 用户直接把聊天端点粘进来也要能拼对（常见误操作）
        assertEquals(
            "https://api.deepseek.com/v1/models",
            LlmProtocol.modelsEndpoint("https://api.deepseek.com/v1/chat/completions"),
        )
        // 已经是 models 端点则不重复拼
        assertEquals(
            "https://x.example.com/api/v1/models",
            LlmProtocol.modelsEndpoint("https://x.example.com/api/v1/models"),
        )
        assertEquals("", LlmProtocol.modelsEndpoint("  "))
    }

    @Test
    fun `解析 OpenAI 官方 data 数组`() {
        val raw = """{"object":"list","data":[{"id":"gpt-4o"},{"id":"gpt-4o-mini"}]}"""
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), LlmProtocol.parseModels(raw))
    }

    @Test
    fun `解析裸数组与 models 字段两种非标准形态`() {
        assertEquals(listOf("a", "b"), LlmProtocol.parseModels("""[{"id":"b"},{"id":"a"}]"""))
        assertEquals(listOf("m1"), LlmProtocol.parseModels("""{"models":[{"id":"m1"}]}"""))
    }

    @Test
    fun `兼容 model 与 name 字段而不是 id`() {
        val raw = """{"data":[{"model":"deepseek-chat"},{"name":"qwen-max"}]}"""
        assertEquals(listOf("deepseek-chat", "qwen-max"), LlmProtocol.parseModels(raw))
    }

    @Test
    fun `无法解析时返回空列表而不抛异常`() {
        // 拉列表失败不该把设置页卡住：返回空，由调用方决定提示
        assertEquals(emptyList<String>(), LlmProtocol.parseModels(""))
        assertEquals(emptyList<String>(), LlmProtocol.parseModels("not json"))
        assertEquals(emptyList<String>(), LlmProtocol.parseModels("""{"unexpected":1}"""))
    }

    @Test
    fun `模型列表去重并排序`() {
        val raw = """{"data":[{"id":"b"},{"id":"a"},{"id":"b"}]}"""
        assertEquals(listOf("a", "b"), LlmProtocol.parseModels(raw))
    }

    @Test
    fun `authHeader 重载可直接接受 apiKey`() {
        assertEquals("Bearer sk-x", LlmProtocol.authHeader(" sk-x "))
        assertEquals(null, LlmProtocol.authHeader("   "))
        assertEquals(null, LlmProtocol.authHeader(""))
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

    // ===== v0.2.70：终端助手用更保守的生成参数 =====

    @Test
    fun `助手参数覆盖配置里的温度`() {
        // 写提示词用配置值（默认 0.9），但生成运维命令要稳定 —— 必须能显式压低。
        val body = JSONObject(
            LlmProtocol.buildRequestBody(
                config = LlmConfig(baseUrl = "https://x", model = "m", temperature = 0.9f),
                systemPrompt = "SYS",
                userMessage = "U",
                temperature = LlmProtocol.ASSISTANT_TEMPERATURE,
                maxTokens = LlmProtocol.ASSISTANT_MAX_TOKENS,
            ),
        )
        assertEquals(
            LlmProtocol.ASSISTANT_TEMPERATURE.toDouble(),
            body.getDouble("temperature"),
            0.001,
        )
        assertEquals(LlmProtocol.ASSISTANT_MAX_TOKENS, body.getInt("max_tokens"))
    }

    @Test
    fun `助手温度明显低于写提示词的默认值`() {
        assertTrue(
            "命令生成要可预期，温度必须低于写提示词的默认值",
            LlmProtocol.ASSISTANT_TEMPERATURE < LlmConfig.DEFAULT_TEMPERATURE,
        )
    }

    @Test
    fun `不传参数时用配置值且不带 max_tokens`() {
        // 写提示词的场景：长短由模型决定，不要凭空加限制（部分中转站不认这个字段）
        val body = JSONObject(
            LlmProtocol.buildRequestBody(
                LlmConfig(baseUrl = "https://x", model = "m", temperature = 0.7f),
                "SYS",
                "U",
            ),
        )
        assertEquals(0.7, body.getDouble("temperature"), 0.001)
        assertTrue("默认不该带 max_tokens", !body.has("max_tokens"))
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
    fun `未知 preset id 回落到内置默认`() {
        assertEquals(
            PromptPresets.DEFAULT_PRESET_ID,
            PromptPresets.find("不存在的", emptyList()).id,
        )
        assertEquals(
            PromptPresets.DEFAULT_PRESET_ID,
            PromptPresets.find(null, emptyList()).id,
        )
        assertEquals("anima", PromptPresets.find("anima", emptyList()).id)
    }

    @Test
    fun `自定义预设可被选中且优先于内置兜底`() {
        val custom = PromptPreset(
            id = "custom-1",
            label = "我的预设",
            hint = "自定义",
            systemPrompt = "CUSTOM RULES",
            builtin = false,
        )
        val found = PromptPresets.find("custom-1", listOf(custom))
        assertEquals("我的预设", found.label)
        assertEquals("CUSTOM RULES", found.systemPrompt)
        // 内置与自定义合并后的完整列表
        assertEquals(3, PromptPresets.all(listOf(custom)).size)
    }

    @Test
    fun `Anima 预设的 system prompt 含固定质量前缀且默认 safe`() {
        val anima = PromptPresets.find("anima", emptyList())
        val prompt = LlmPrompts.systemPrompt(anima, isNegative = false)
        assertTrue(prompt.contains("masterpiece, very aesthetic"))
        assertTrue(prompt.contains("year 2025"))
        assertTrue(prompt.contains("safe"))
    }

    @Test
    fun `自定义预设正文被原样使用`() {
        val custom = PromptPreset(
            id = "custom-2",
            label = "X",
            hint = "",
            systemPrompt = "ONLY MY RULES",
            builtin = false,
        )
        val prompt = LlmPrompts.systemPrompt(custom, isNegative = false)
        assertTrue(prompt.contains("ONLY MY RULES"))
        // 不能把内置规则也塞进去，否则自定义形同虚设
        assertTrue(!prompt.contains("comma-separated short phrases"))
    }

    @Test
    fun `工作流上下文会被写进 system prompt`() {
        val prompt = LlmPrompts.systemPrompt(
            PromptPresets.find("general", emptyList()),
            isNegative = false,
            context = LlmPrompts.WorkflowContext(
                checkpoint = "anima_base_v1.safetensors",
                loras = listOf("loras/角色A.safetensors", "ANIMA-风格.sft"),
            ),
        )
        // 模型名去掉了目录与权重后缀，便于模型理解
        assertTrue(prompt.contains("anima_base_v1"))
        assertTrue(prompt.contains("角色A"))
        assertTrue(prompt.contains("ANIMA-风格"))
        assertTrue(prompt.contains("Base model"))
    }

    @Test
    fun `空工作流上下文不污染 system prompt`() {
        val base = LlmPrompts.systemPrompt(PromptPresets.find("general", emptyList()), isNegative = false)
        val withEmpty = LlmPrompts.systemPrompt(
            PromptPresets.find("general", emptyList()),
            isNegative = false,
            context = LlmPrompts.WorkflowContext(),
        )
        assertEquals(base, withEmpty)
    }

    @Test
    fun `负向字段会改写 system prompt 而不是沿用正向规则`() {
        val negative = LlmPrompts.systemPrompt(PromptPresets.find("general", emptyList()), isNegative = true)
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
