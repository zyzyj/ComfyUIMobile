package com.local.comfyuimobile.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.97 新增的两处纯逻辑单测：
 *
 *  1. [McpErrorSummary] —— traceback 截断（防超大报错撑爆模型上下文）
 *  2. [McpServerManager.configSnippet] 的免鉴权分支（F1：默认不带 headers）
 *
 * 两处都是"看着对但很容易错"的逻辑：截断要保住真正有用的异常行；
 * 免鉴权模式若还带着空 token 的 headers，AiCode 会因为鉴权头不匹配而 401。
 */
class McpErrorSummaryTest {

    private val traceback = """
        Traceback (most recent call last):
          File "/comfy/execution.py", line 510, in execute
            outputs = get_output_data(obj, input_data_all)
          File "/comfy/execution.py", line 97, in get_output_data
            return_values = map_node_over_list(obj, input_data_all, obj.FUNCTION, allow_interrupt=True)
          File "/comfy/execution.py", line 139, in map_node_over_list
            results.append(getattr(obj, func)(**slice_dict(input_data_all, i)))
          File "/comfy/model_management.py", line 402, in load_models_gpu
            raise RuntimeError(msg)
        RuntimeError: Allocate 8.00 GiB for {'model': 'sdxl'} failed. No free memory
    """.trimIndent()

    @Test
    fun longTracebackKeepsLastLinesAndDropsFrames() {
        val summary = McpErrorSummary.summarize(traceback)
        // 最关键的一行（异常类型 + 消息）必须保住。
        assertTrue(summary, summary.contains("RuntimeError: Allocate 8.00 GiB"))
        // 栈帧不该出现——那是噪音。
        assertFalse(summary, summary.contains("execution.py"))
        assertTrue("应提示省略了多少行", summary.contains("省略"))
    }

    @Test
    fun shortTextPassesThroughUnchanged() {
        val short = "OOM: only 1.2 GiB free"
        assertEquals(short, McpErrorSummary.summarize(short))
    }

    @Test
    fun blankBecomesEmpty() {
        assertEquals("", McpErrorSummary.summarize("   \n  "))
    }

    @Test
    fun resultNeverExceedsLimit() {
        val huge = "x".repeat(5000)
        assertTrue(McpErrorSummary.summarize(huge).length <= McpErrorSummary.MAX_CHARS + 20)
    }
}

class McpConfigSnippetTest {

    @Test
    fun noAuthModeOmitsHeaders() {
        val snippet = McpServerManager.configSnippet("tok123", 23456, requireAuth = false)
        assertFalse("免鉴权模式不能带 Authorization 头", snippet.contains("Authorization"))
        assertTrue(snippet.contains("http://127.0.0.1:23456/mcp"))
    }

    @Test
    fun authModeIncludesBearerHeader() {
        val snippet = McpServerManager.configSnippet("tok123", 23456, requireAuth = true)
        assertTrue(snippet.contains("Bearer tok123"))
    }

    @Test
    fun authModeWithBlankTokenOmitsHeadersInsteadOfEmptyBearer() {
        // 空 token 却写 "Bearer " 只会让 AiCode 侧一直 401，不如不写。
        val snippet = McpServerManager.configSnippet("", 23456, requireAuth = true)
        assertFalse(snippet.contains("Bearer"))
    }
}
