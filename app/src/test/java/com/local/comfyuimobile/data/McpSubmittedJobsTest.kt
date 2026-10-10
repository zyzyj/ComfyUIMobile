package com.local.comfyuimobile.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已提交任务记录的编解码单测（v0.3.7）。
 *
 * 重点在**向后兼容**：旧版本存的是裸 id 数组。解析不出来就等于把老用户已提交的
 * 任务列表清空——表现是「任务跟踪又失灵了」，正是本工具要修的那个病。
 */
class McpSubmittedJobsTest {

    @Test
    fun roundTripsRecords() {
        val records = listOf(
            SubmittedJobRecord("a1b2", submittedAt = 1_700_000_000_000L, fetched = true),
            SubmittedJobRecord("c3d4", submittedAt = 1_700_000_100_000L, fetched = false),
        )
        assertEquals(records, McpSubmittedJobs.decode(McpSubmittedJobs.encode(records)))
    }

    @Test
    fun readsLegacyPlainIdArray() {
        // 旧格式：["id1","id2"]。必须认，且时刻标为 0（"未知"）而不是编一个当前时间。
        val legacy = JSONArray().put("old-1").put("old-2").toString()
        val decoded = McpSubmittedJobs.decode(legacy)
        assertEquals(2, decoded.size)
        assertEquals("old-1", decoded[0].jobId)
        assertEquals(0L, decoded[0].submittedAt)
        assertFalse(decoded[0].fetched)
    }

    @Test
    fun skipsCorruptEntriesButKeepsTheRest() {
        // 一条坏数据不该毁掉全部记录：空 id / 非法类型都跳过。
        val raw = JSONArray()
            .put(org.json.JSONObject().put("id", "good").put("at", 123L))
            .put(org.json.JSONObject().put("id", ""))
            .put(42)
            .put("legacy-id")
            .toString()
        val decoded = McpSubmittedJobs.decode(raw)
        assertEquals(listOf("good", "legacy-id"), decoded.map { it.jobId })
    }

    @Test
    fun malformedJsonYieldsEmptyInsteadOfThrowing() {
        assertEquals(emptyList<SubmittedJobRecord>(), McpSubmittedJobs.decode("{不是数组"))
        assertEquals(emptyList<SubmittedJobRecord>(), McpSubmittedJobs.decode(""))
    }

    @Test
    fun overwriteKeepsKnownTimestampsAndFetchedFlags() {
        // 界面提交后走整体覆盖（ids + newId）。若重建记录，MCP 记的提交时刻会被抹掉。
        val existing = listOf(SubmittedJobRecord("a", submittedAt = 999L, fetched = true))
        val merged = McpSubmittedJobs.mergeForOverwrite(existing, listOf("a", "b"))
        assertEquals(2, merged.size)
        assertEquals(999L, merged[0].submittedAt)
        assertTrue(merged[0].fetched)
        assertEquals(0L, merged[1].submittedAt)
    }

    @Test
    fun overwriteDropsBlankIds() {
        val merged = McpSubmittedJobs.mergeForOverwrite(emptyList(), listOf("", "  ", "x"))
        assertEquals(listOf("x"), merged.map { it.jobId })
    }
}
