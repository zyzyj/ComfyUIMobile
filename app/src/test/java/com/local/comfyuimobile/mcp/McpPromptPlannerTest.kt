package com.local.comfyuimobile.mcp

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
    ) = GenerateRequest(prompt, negative, workflow, checkpoint, lora, count)

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
