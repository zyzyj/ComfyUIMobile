package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.model.ParameterField
import com.local.comfyuimobile.model.ParameterKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词注入的单测（v0.2.85）。
 *
 * 这里最要紧的不是"能改成功"，而是**改不成功时必须失败**。注入错节点会在服务器上
 * 产出一张完全不相干的图——比直接报错难排查得多（属于"生成物交给外部系统后语义
 * 变化"那一类）。所以每个"找不到目标"的分支都要有明确断言。
 */
class McpPromptPlannerTest {

    /** 一份最小但结构完整的 API 格式工作流。 */
    private fun apiWorkflow(): JSONObject = JSONObject(
        """
        {
          "3":  {"class_type":"KSampler","inputs":{"positive":["6",0],"negative":["7",0],"seed":1,"steps":20}},
          "4":  {"class_type":"CheckpointLoaderSimple","inputs":{"ckpt_name":"sd_xl.safetensors"}},
          "6":  {"class_type":"CLIPTextEncode","inputs":{"text":"old positive","clip":["4",1]}},
          "7":  {"class_type":"CLIPTextEncode","inputs":{"text":"old negative","clip":["4",1]}},
          "8":  {"class_type":"EmptyLatentImage","inputs":{"width":512,"height":512,"batch_size":1}},
          "9":  {"class_type":"VAEDecode","inputs":{"samples":["3",0],"vae":["4",2]}},
          "10": {"class_type":"SaveImage","inputs":{"images":["9",0]}}
        }
        """.trimIndent(),
    )

    private fun request(
        prompt: String = "a cat",
        negative: String = "",
        workflow: String? = null,
        checkpoint: String? = null,
        lora: String? = null,
        count: Int = 1,
        params: Map<String, String> = emptyMap(),
    ) = GenerateRequest(prompt, negative, workflow, checkpoint, lora, count, params)

    // ===== v0.2.91：任意参数注入 =====

    @Test
    fun applyParamsWritesIntegerFieldsAsNumbers() {
        // seed/steps 这类整数字段必须是数字：直接塞字符串会让服务端报类型错，
        // 或者（更糟）被当成别的东西。
        val result = McpPromptPlanner.applyParams(apiWorkflow(), mapOf("3::steps" to "30"))
        val prompt = JSONObject(result.promptJson)
        val steps = prompt.getJSONObject("3").getJSONObject("inputs").get("steps")
        assertTrue("steps 必须是数字而非字符串", steps is Int || steps is Long)
        assertEquals(30L, (steps as Number).toLong())
        assertEquals(1, result.applied.size)
        assertTrue(result.unknownKeys.isEmpty())
    }

    @Test
    fun applyParamsReportsUnknownKeys() {
        // 静默忽略最危险：模型以为改了其实没改。必须报出来。
        val result = McpPromptPlanner.applyParams(apiWorkflow(), mapOf("3::nonexistent" to "1"))
        assertTrue(result.unknownKeys.contains("3::nonexistent"))
        assertTrue(result.applied.isEmpty())
    }

    @Test
    fun applyParamsRejectsNonNumericForIntegerField() {
        val result = McpPromptPlanner.applyParams(apiWorkflow(), mapOf("3::steps" to "abc"))
        assertTrue("类型不对应算未识别，不该写坏工作流", result.unknownKeys.contains("3::steps"))
    }

    @Test
    fun applyParamsDoesNotMutateInput() {
        val base = apiWorkflow()
        McpPromptPlanner.applyParams(base, mapOf("3::steps" to "30"))
        assertEquals(20, base.getJSONObject("3").getJSONObject("inputs").getInt("steps"))
    }

    @Test
    fun applyParamsWithEmptyMapIsNoop() {
        val base = apiWorkflow()
        val result = McpPromptPlanner.applyParams(base, emptyMap())
        assertTrue(result.unknownKeys.isEmpty())
        assertTrue(result.applied.isEmpty())
    }

    @Test
    fun fieldsExposesInjectableKeys() {
        // describe_workflow 靠它给模型 key 清单；至少要有采样器那几个整数字段。
        val keys = McpPromptPlanner.fields(apiWorkflow()).map { it.key }
        assertTrue("应能列出 steps", keys.any { it.endsWith("steps") })
        // 连线字段（如 KSampler 的 positive）改不动，不能出现在清单里。
        assertTrue("连线字段不该被列出", keys.none { it.endsWith("positive") })
    }

    // ===== v0.2.95：展示顺序 =====

    private fun field(name: String, nodeId: String = "3", title: String = "KSampler"): ParameterField =
        ParameterField(
            key = "$nodeId::$name",
            nodeId = nodeId,
            nodeTitle = title,
            nodeType = "KSampler",
            name = name,
            label = name,
            widgetType = "",
            kind = ParameterKind.INTEGER,
            valueJson = "1",
            displayValue = "1",
        )

    @Test
    fun mostUsedFieldsComeFirst() {
        // 核心回归：以前 compareByDescending 让 height(6) 排第一、steps(0) 排最后。
        val ordered = McpPromptPlanner.orderForDisplay(
            listOf(field("height"), field("width"), field("seed"), field("steps"), field("cfg")),
        ).map { it.name }
        assertEquals("steps 应排在最前", "steps", ordered.first())
        assertEquals("cfg 应紧随其后", "cfg", ordered[1])
        // 常用项整体必须排在非常用项之前。
        val lastPriority = ordered.indexOf("height")
        assertTrue(ordered.indexOf("steps") < lastPriority)
    }

    @Test
    fun unknownFieldsGoToTheEnd() {
        val ordered = McpPromptPlanner.orderForDisplay(
            listOf(field("zzz_unknown"), field("steps"), field("foo")),
        ).map { it.name }
        assertEquals("steps", ordered.first())
        assertTrue(ordered.indexOf("zzz_unknown") > ordered.indexOf("steps"))
        assertTrue(ordered.indexOf("foo") > ordered.indexOf("steps"))
    }

    @Test
    fun orderingIsCaseInsensitiveOnFieldName() {
        // ComfyUI 里 sampler_name / Sampler_name 都可能出现。
        val ordered = McpPromptPlanner.orderForDisplay(listOf(field("ZZZ"), field("STEPS")))
            .map { it.name }
        assertEquals("STEPS", ordered.first())
    }

    @Test
    fun orderingIsStableForEqualPriority() {
        val ordered = McpPromptPlanner.orderForDisplay(
            listOf(field("b", nodeId = "9", title = "ZZZ"), field("a", nodeId = "3", title = "AAA")),
        )
        // 都是非常用项时按节点名、字段名排，结果应稳定可复现。
        assertEquals(McpPromptPlanner.orderForDisplay(ordered.reversed()), ordered)
    }

    @Test
    fun fieldsIsEmptyForInvalidWorkflow() {
        assertTrue(McpPromptPlanner.fields(JSONObject("{\"nodes\":[]}")).isEmpty())
    }

    private fun ok(result: McpPromptPlanner.Result): McpPromptPlanner.Result.Ok {
        assertTrue("期望成功，实际 $result", result is McpPromptPlanner.Result.Ok)
        return result as McpPromptPlanner.Result.Ok
    }

    @Test
    fun writesPositivePromptIntoLinkedPromptNode() {
        val result = ok(McpPromptPlanner.plan(apiWorkflow(), request(prompt = "a dog")))
        val prompt = JSONObject(result.promptJson)
        assertEquals("a dog", prompt.getJSONObject("6").getJSONObject("inputs").getString("text"))
        assertEquals("old negative", prompt.getJSONObject("7").getJSONObject("inputs").getString("text"))
        assertTrue(result.applied.contains("prompt"))
    }

    @Test
    fun negativeOnlyWrittenWhenProvided() {
        val withNegative = ok(McpPromptPlanner.plan(apiWorkflow(), request(negative = "blurry")))
        assertEquals(
            "blurry",
            JSONObject(withNegative.promptJson).getJSONObject("7").getJSONObject("inputs").getString("text"),
        )
        val without = ok(McpPromptPlanner.plan(apiWorkflow(), request()))
        assertEquals(
            "old negative",
            JSONObject(without.promptJson).getJSONObject("7").getJSONObject("inputs").getString("text"),
        )
    }

    @Test
    fun doesNotMutateInputWorkflow() {
        // 深拷贝：失败或调用方复用同一份 base 时，不能被上次的注入污染。
        val base = apiWorkflow()
        McpPromptPlanner.plan(base, request(prompt = "changed"))
        assertEquals("old positive", base.getJSONObject("6").getJSONObject("inputs").getString("text"))
    }

    @Test
    fun writesCheckpointOnlyWhenSpecified() {
        val result = ok(McpPromptPlanner.plan(apiWorkflow(), request(checkpoint = "other.safetensors")))
        assertEquals(
            "other.safetensors",
            JSONObject(result.promptJson).getJSONObject("4").getJSONObject("inputs").getString("ckpt_name"),
        )
        val untouched = ok(McpPromptPlanner.plan(apiWorkflow(), request()))
        assertEquals(
            "sd_xl.safetensors",
            JSONObject(untouched.promptJson).getJSONObject("4").getJSONObject("inputs").getString("ckpt_name"),
        )
    }

    @Test
    fun countAboveOneInjectsBatchSize() {
        val result = ok(McpPromptPlanner.plan(apiWorkflow(), request(count = 4)))
        assertEquals(
            4,
            JSONObject(result.promptJson).getJSONObject("8").getJSONObject("inputs").getInt("batch_size"),
        )
        assertTrue(result.applied.contains("batch_size"))
    }

    @Test
    fun failsWhenNoPromptNodeExists() {
        val noPrompt = JSONObject(
            """{"4":{"class_type":"CheckpointLoaderSimple","inputs":{"ckpt_name":"x"}},
                "10":{"class_type":"SaveImage","inputs":{"images":["4",0]}}}""".trimIndent(),
        )
        val result = McpPromptPlanner.plan(noPrompt, request())
        assertTrue(result is McpPromptPlanner.Result.Failure)
        assertTrue((result as McpPromptPlanner.Result.Failure).message.contains("CLIPTextEncode"))
    }

    @Test
    fun failsWhenLoraRequestedButNoLoraNode() {
        val result = McpPromptPlanner.plan(apiWorkflow(), request(lora = "style.safetensors"))
        // 静默忽略会让用户以为 LoRA 生效了、拿到一张没加 LoRA 的图，必须明确失败。
        assertTrue(result is McpPromptPlanner.Result.Failure)
        assertTrue((result as McpPromptPlanner.Result.Failure).message.contains("LoRA"))
    }

    @Test
    fun failsOnNonApiWorkflow() {
        val canvas = JSONObject("""{"nodes":[{"id":1,"type":"KSampler"}],"links":[]}""")
        val result = McpPromptPlanner.plan(canvas, request())
        assertTrue(result is McpPromptPlanner.Result.Failure)
    }

    @Test
    fun toleratesWorkflowWithoutSampler() {
        // 没有采样器连线时退回"执行链里第一个提示词节点"，而不是直接失败。
        val noSampler = JSONObject(
            """{"6":{"class_type":"CLIPTextEncode","inputs":{"text":"old","clip":["4",1]}},
                "4":{"class_type":"CheckpointLoaderSimple","inputs":{"ckpt_name":"x"}},
                "10":{"class_type":"SaveImage","inputs":{"images":["6",0]}}}""".trimIndent(),
        )
        val result = ok(McpPromptPlanner.plan(noSampler, request(prompt = "new")))
        assertEquals(
            "new",
            JSONObject(result.promptJson).getJSONObject("6").getJSONObject("inputs").getString("text"),
        )
    }
}
