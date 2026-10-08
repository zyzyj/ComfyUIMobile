package com.local.comfyuimobile.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作流格式标记单测（v0.2.99，懒加载版）。
 *
 * `labelOf` 的契约要点：**不知道就返回 null，绝不猜**——
 * UI 只显示已有本机内容的格式，不为了一个标签发网络请求。
 * 断言先用 Python 复刻同一套判定跑过 10 个用例后才落成。
 */
class WorkflowFormatLabelTest {

    @Test
    fun apiPromptGetsApiLabel() {
        assertEquals(
            "API",
            WorkflowFormat.labelOf("""{"3":{"class_type":"KSampler","inputs":{}}}"""),
        )
        assertEquals(
            "API",
            WorkflowFormat.labelOf("""{"3":{"class_type":"KSampler"},"4":{"class_type":"SaveImage"}}"""),
        )
    }

    @Test
    fun canvasGetsCanvasLabel() {
        assertEquals(
            "画布",
            WorkflowFormat.labelOf("""{"nodes":[{"id":1,"type":"KSampler"}],"links":[]}"""),
        )
    }

    @Test
    fun unknownShapesReturnNullNotGuess() {
        // 空对象：既不是 API（没条目）也不是画布（没 nodes）——不猜。
        assertNull(WorkflowFormat.labelOf("{}"))
        // 条目缺 class_type：不完整，不猜。
        assertNull(WorkflowFormat.labelOf("""{"3":{"inputs":{}}}"""))
        // 部分条目缺 class_type：同样不猜（isApiPrompt 要求全部命中）。
        assertNull(WorkflowFormat.labelOf("""{"3":{"class_type":"A"},"4":{"inputs":{}}}"""))
        // 顶层是数组：两种格式都不是。
        assertNull(WorkflowFormat.labelOf("[1,2,3]"))
    }

    @Test
    fun blankOrInvalidContentReturnsNull() {
        assertNull(WorkflowFormat.labelOf(null))
        assertNull(WorkflowFormat.labelOf(""))
        assertNull(WorkflowFormat.labelOf("   "))
        assertNull(WorkflowFormat.labelOf("not json"))
    }

    @Test
    fun isApiPromptStillRejectsCanvasAndEmpty() {
        // 既有判定的回归保护（labelOf 建立在它上面）。
        val canvas = JSONObject("""{"nodes":[],"links":[]}""")
        assertTrue(WorkflowFormat.isCanvas(canvas))
        assertFalse(WorkflowFormat.isApiPrompt(canvas))
        assertFalse(WorkflowFormat.isApiPrompt(JSONObject("{}")))
    }
}
