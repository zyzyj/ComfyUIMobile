package com.local.comfyuimobile.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.43：任务失败原因的解析测试。
 *
 * 以前任务列表对失败任务只显示 status_str（就一个 "error"），用户看不出为什么失败。
 * 这里锁住的是「从 history 的 status.messages 里把真实原因抽出来」这条逻辑。
 */
class ExecutionErrorTest {

    private fun status(vararg messages: Any): JSONObject {
        val array = JSONArray()
        messages.forEach { array.put(it) }
        return JSONObject().put("messages", array)
    }

    private fun errorEvent(nodeId: String, nodeType: String, message: String): JSONArray =
        JSONArray().put("execution_error").put(
            JSONObject()
                .put("node_id", nodeId)
                .put("node_type", nodeType)
                .put("exception_type", "RuntimeError")
                .put("exception_message", message),
        )

    @Test
    fun extractsExceptionMessageWithNodeType() {
        val s = status(errorEvent("3", "KSampler", "CUDA out of memory."))
        assertEquals("KSampler：CUDA out of memory.", ExecutionError.describe(s))
    }

    @Test
    fun prefersNodeTitleWhenAvailable() {
        val s = status(errorEvent("3", "KSampler", "CUDA out of memory."))
        assertEquals(
            "采样器：CUDA out of memory.",
            ExecutionError.describe(s, mapOf("3" to "采样器")),
        )
    }

    @Test
    fun fallsBackToNodeIdWhenTypeMissing() {
        val event = JSONArray().put("execution_error").put(
            JSONObject().put("node_id", "7").put("exception_message", "boom"),
        )
        val s = status(event)
        assertEquals("节点 7：boom", ExecutionError.describe(s))
    }

    @Test
    fun reportsInterrupted() {
        val event = JSONArray().put("execution_interrupted").put(JSONObject().put("node_id", "1"))
        assertEquals("任务被中断", ExecutionError.describe(status(event)))
    }

    @Test
    fun returnsEmptyWhenNoFailureEvent() {
        val success = JSONArray().put("execution_success").put(JSONObject().put("timestamp", 1))
        assertEquals("", ExecutionError.describe(status(success)))
        assertEquals("", ExecutionError.describe(null))
        assertEquals("", ExecutionError.describe(JSONObject()))
    }

    @Test
    fun usesLastFailureEventWhenRerun() {
        val s = status(
            errorEvent("1", "First", "first failure"),
            errorEvent("2", "Second", "second failure"),
        )
        assertTrue(ExecutionError.describe(s).startsWith("Second"))
    }
}