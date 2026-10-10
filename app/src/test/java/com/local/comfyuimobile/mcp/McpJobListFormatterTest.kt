package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.data.SubmittedJobRecord
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `list_my_jobs` 输出组装单测（v0.3.7）。
 *
 * 这个工具存在的唯一意义是「让 AI 丢了 job_id 时别重新提交」。所以最关键的一条
 * 断言是：输出里**必须**带"不要重新提交"的引导——它被改掉就等于工具白做。
 */
class McpJobListFormatterTest {

    private fun entry(id: String, at: Long = 0L, fetched: Boolean = false, label: String = "done", images: Boolean = true) =
        McpJobListFormatter.Entry(
            record = SubmittedJobRecord(jobId = id, submittedAt = at, fetched = fetched),
            stateLabel = label,
            hasImages = images,
        )

    @Test
    fun alwaysTellsModelNotToResubmit() {
        val text = McpJobListFormatter.render(listOf(entry("a1b2c3d4e5f6")), totalShown = 1)
        assertTrue("输出必须包含「不要用 generate 重新提交」的引导", text.contains("不要用 generate 重新提交"))
        assertTrue(text.contains("再烧一次算力卡"))
    }

    @Test
    fun emptyListExplainsItselfWithOutNudgingResubmit() {
        // 空列表时若不说清"记录可能被清理"，AI 会一直怀疑工具坏了。
        val text = McpJobListFormatter.render(emptyList(), totalShown = 0)
        assertTrue(text.contains("没有已提交的任务记录"))
        assertTrue(text.contains("记录已被清理"))
    }

    @Test
    fun pointsOutUnfetchedTasks() {
        val text = McpJobListFormatter.render(
            listOf(entry("done1", fetched = false), entry("done2", fetched = true)),
            totalShown = 2,
        )
        assertTrue("应对未取图的任务给出 action", text.contains("还没取图"))
        assertTrue(text.contains("job_status(job_ids="))
    }

    @Test
    fun doesNotClaimUnfetchedWhenAllFetched() {
        val text = McpJobListFormatter.render(
            listOf(entry("done1", fetched = true), entry("done2", fetched = true)),
            totalShown = 2,
        )
        assertTrue(!text.contains("还没取图"))
    }

    @Test
    fun marksUnknownTimeHonestly() {
        // 旧记录没有提交时刻，必须说"时间未知"而不是编一个。
        val text = McpJobListFormatter.render(listOf(entry("legacy", at = 0L)), totalShown = 1)
        assertTrue(text.contains("时间未知"))
    }

    @Test
    fun rendersTimestampWhenKnown() {
        val text = McpJobListFormatter.render(
            listOf(entry("x", at = 1_700_000_000_000L)),
            totalShown = 1,
        )
        assertTrue(!text.contains("时间未知"))
    }

    @Test
    fun tagsUnfetchedOnTheLine() {
        val text = McpJobListFormatter.render(listOf(entry("abc", fetched = false)), totalShown = 1)
        assertTrue(text.contains("← 未取图"))
    }

    @Test
    fun saysStateUnknownWhenStatusMissing() {
        val text = McpJobListFormatter.render(listOf(entry("abc", label = "")), totalShown = 1)
        assertTrue(text.contains("状态未知"))
    }
}
