package com.local.comfyuimobile.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作流预检单测（v0.2.96）。
 *
 * 每类校验都要有"能抓到"的用例——它是**省算力卡**的功能，漏报等于没做；
 * 也要有"不误报"的用例——误报会让 AI 不敢提交正常的工作流。
 */
class McpWorkflowValidatorTest {

    /** 一份可用的最小 API 格式工作流。 */
    private fun validWorkflow(): JSONObject = JSONObject(
        """
        {
          "3": {"class_type":"KSampler","inputs":{"positive":["6",0],"negative":["6",0],"seed":1,"steps":20}},
          "4": {"class_type":"CheckpointLoaderSimple","inputs":{"ckpt_name":"sd.safetensors"}},
          "6": {"class_type":"CLIPTextEncode","inputs":{"text":"a cat","clip":["4",1]}},
          "10":{"class_type":"SaveImage","inputs":{"images":["3",0]}}
        }
        """.trimIndent(),
    )

    private val fullCatalog = setOf("KSampler", "CheckpointLoaderSimple", "CLIPTextEncode", "SaveImage")

    @Test
    fun validWorkflowPasses() {
        val report = McpWorkflowValidator.validate(validWorkflow(), fullCatalog)
        assertTrue("正常应通过，实际错误：${report.errors}", report.ok)
        assertTrue(report.render().contains("可以提交"))
    }

    @Test
    fun missingNodeTypeIsAnError() {
        // 换机器 / 换镜像后最常见的问题：节点没装。
        val report = McpWorkflowValidator.validate(validWorkflow(), fullCatalog - "KSampler")
        assertFalse(report.ok)
        assertTrue(report.errors.any { it.contains("KSampler") })
    }

    @Test
    fun nullCatalogSkipsNodeCheck() {
        // catalog 为 null = 这次没查到，不能因此冤枉工作流（与"查到 0 个"必须区分）。
        val report = McpWorkflowValidator.validate(validWorkflow(), null)
        assertTrue("拿不到节点清单时不该报节点错误", report.ok)
    }

    @Test
    fun danglingLinkIsAnError() {
        // 手改 JSON 的头号错误：删了节点忘了改引用。
        // 注意这里必须能抓到——解析器会把悬空引用当普通 widget 值，
        // 所以校验器是自己扫原始 JSON 的（下方 numericalArray 用例锁住不误报）。
        val broken = JSONObject(
            """
            {
              "3": {"class_type":"KSampler","inputs":{"positive":["999",0],"seed":1}},
              "10":{"class_type":"SaveImage","inputs":{"images":["3",0]}}
            }
            """.trimIndent(),
        )
        val report = McpWorkflowValidator.validate(broken, null)
        assertFalse(report.ok)
        assertTrue("应指出指向 999 的连线", report.errors.any { it.contains("999") })
    }

    @Test
    fun numericalArraysAreNotMistakenForLinks() {
        // resolution:[1024,1024] 这类数字数组不是连线。判定要求首元素是**字符串**，
        // 否则会把所有双数字参数都误报成悬空连线——那会让预检变成噪音。
        val withResolution = JSONObject(
            """
            {
              "3": {"class_type":"KSampler","inputs":{"resolution":[1024,1024],"seed":1}},
              "10":{"class_type":"SaveImage","inputs":{"images":["3",0]}}
            }
            """.trimIndent(),
        )
        val report = McpWorkflowValidator.validate(withResolution, null)
        assertTrue("数字数组不该被当成连线，实际错误：${report.errors}", report.ok)
    }

    @Test
    fun nonExistentUpstreamIsCaughtEvenThoughParserHidesIt() {
        // 设计约束回归：如果将来有人把实现改回用 parsed.links，这条会立刻失败——
        // 因为解析器把 "首元素不在节点表里" 的数组判为非连线，悬空引用就查不到了。
        val oneNode = JSONObject(
            """{"10":{"class_type":"SaveImage","inputs":{"images":["3",0]}}}""".trimIndent(),
        )
        val report = McpWorkflowValidator.validate(oneNode, null)
        assertTrue("必须能看到指向不存在节点 3 的连线", report.errors.any { it.contains("3") })
    }

    @Test
    fun missingOutputNodeIsAnError() {
        // 没有 SaveImage 一类节点 → 跑完不出图，白烧算力。
        val noOutput = JSONObject(
            """
            {
              "3": {"class_type":"KSampler","inputs":{"seed":1}},
              "4": {"class_type":"CheckpointLoaderSimple","inputs":{"ckpt_name":"x"}}
            }
            """.trimIndent(),
        )
        val report = McpWorkflowValidator.validate(noOutput, null)
        assertFalse(report.ok)
        assertTrue(report.errors.any { it.contains("输出节点") })
    }

    @Test
    fun emptyWorkflowIsRejected() {
        val report = McpWorkflowValidator.validate(JSONObject("{}"), fullCatalog)
        assertFalse(report.ok)
    }

    @Test
    fun nonApiWorkflowIsRejected() {
        // 画布格式（有 nodes 数组）解析不出节点。
        val canvas = JSONObject("""{"nodes":[{"id":1,"type":"KSampler"}],"links":[]}""")
        val report = McpWorkflowValidator.validate(canvas, fullCatalog)
        assertFalse(report.ok)
        assertTrue(report.render().contains("请先修复再提交"))
    }

    @Test
    fun missingSamplerIsOnlyAWarning() {
        // 没有采样器通常是异常链路，但不该直接拦住——有的工作流用第三方采样器。
        val noSampler = JSONObject(
            """
            {
              "4": {"class_type":"CheckpointLoaderSimple","inputs":{"ckpt_name":"x"}},
              "10":{"class_type":"SaveImage","inputs":{"images":["4",0]}}
            }
            """.trimIndent(),
        )
        val report = McpWorkflowValidator.validate(noSampler, null)
        assertTrue("应只是提醒，不算错误", report.ok)
        assertTrue(report.warnings.isNotEmpty())
    }

    @Test
    fun renderTellsModelNotToSubmitWhenFailed() {
        // 失败时必须明确说"别提交"，否则模型可能抱着侥幸心理试——那正是要避免的
        // 白烧算力。
        val report = McpWorkflowValidator.validate(JSONObject("{}"), fullCatalog)
        assertTrue(report.render().contains("请先修复再提交"))
        assertFalse(report.render().contains("可以提交"))
    }

    @Test
    fun largeDanglingListIsTruncated() {
        // 错误列表可能很长（AI 大改过工作流），渲染时不该把上下文挤爆。
        val many = StringBuilder("{")
        for (i in 1..10) {
            if (i > 1) many.append(",")
            many.append(""""$i":{"class_type":"KSampler","inputs":{"positive":["999$i",0]}}""")
        }
        many.append("}")
        val report = McpWorkflowValidator.validate(JSONObject(many.toString()), null)
        assertFalse(report.ok)
        assertTrue("错误说明应截断展示", report.errors.first().length < 400)
    }
}
