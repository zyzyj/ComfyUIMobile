package com.local.comfyuimobile.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v0.1.79：任务耗时的解析规则。
 *
 * 这两条用例是现场问题的反向回归——以前 `ResultParser` 把 `execution_cached`
 * 当终态（耗时被砍成几十毫秒），`ComfyClient` 又不认它（耗时空白），两边谁都不对。
 */
class ExecutionTimingTest {

    private fun status(vararg events: Pair<String, Long>): JSONObject {
        val messages = events.joinToString(",") { (type, ts) -> "[\"$type\",{\"timestamp\":$ts}]" }
        return JSONObject("""{"messages":[$messages]}""")
    }

    @Test fun normalRunUsesSuccessAsEnd() {
        val s = status("execution_start" to 1_000L, "execution_success" to 9_000L)
        assertEquals(8_000L, ExecutionTiming.durationMs(s))
    }

    @Test fun partiallyCachedRunMustNotStopAtCachedEvent() {
        // 部分节点命中缓存：cached 只比 start 晚 50ms，真正的结束是 8 秒后的 success。
        // 旧逻辑在这里会算成 50ms——"耗时不准"的那一半根因。
        val s = status(
            "execution_start" to 1_000L,
            "execution_cached" to 1_050L,
            "execution_success" to 9_000L,
        )
        assertEquals(8_000L, ExecutionTiming.durationMs(s))
        assertEquals(9_000L, ExecutionTiming.end(s))
    }

    @Test fun fullyCachedRunFallsBackToCachedTimestamp() {
        // 整条链路全命中缓存：ComfyUI 只发 cached、不发 success，以前耗时是空白。
        val s = status("execution_start" to 1_000L, "execution_cached" to 1_300L)
        assertEquals(300L, ExecutionTiming.durationMs(s))
    }

    @Test fun errorAndInterruptedAreTerminal() {
        assertEquals(2_000L, ExecutionTiming.durationMs(status("execution_start" to 1_000L, "execution_error" to 3_000L)))
        assertEquals(2_000L, ExecutionTiming.durationMs(status("execution_start" to 1_000L, "execution_interrupted" to 3_000L)))
    }

    @Test fun startFallsBackToEarliestMessageWhenStartEventMissing() {
        val s = status("execution_cached" to 5_000L)
        assertEquals(5_000L, ExecutionTiming.start(s))
        assertNull(ExecutionTiming.durationMs(s))
    }

    @Test fun picksEarliestStartAndLatestTerminalWhenRepeated() {
        val s = status(
            "execution_start" to 2_000L,
            "execution_start" to 1_000L,
            "execution_success" to 4_000L,
        )
        assertEquals(3_000L, ExecutionTiming.durationMs(s))
    }

    @Test fun missingOrBrokenMessagesYieldNothing() {
        assertNull(ExecutionTiming.durationMs(null))
        assertNull(ExecutionTiming.durationMs(JSONObject()))
        assertNull(ExecutionTiming.durationMs(JSONObject("""{"messages":[[{"timestamp":1}]]}""")))
        // 结束早于开始（时钟漂移）不该算出负数
        assertNull(ExecutionTiming.durationMs(status("execution_start" to 9_000L, "execution_success" to 1_000L)))
    }
}
