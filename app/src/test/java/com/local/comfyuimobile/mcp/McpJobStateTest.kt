package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.model.JobState
import com.local.comfyuimobile.model.JobSummary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 任务状态判定单测（v0.2.90）。
 *
 * 堵的是"平行路径"：以前完成判定只读 history、关机判定只读 queue，
 * 于是**排队中的任务**（history 里没有）会被当成运行中，一直轮询到超时。
 * 现在只有 resolve 一个函数回答状态，这里锁住各分支。
 */
class McpJobStateTest {

    private fun verdict(
        id: String,
        queue: List<JobSummary>? = null,
        history: JSONObject? = null,
        hasOutputs: Boolean = false,
    ) = McpJobState.resolve(id, queue, history, hasOutputs)

    @Test
    fun pendingIsReportedAsQueued() {
        // 核心回归：排队中的任务在 history 里不存在——必须靠 queue 判出 queued，
        // 而不是落到 UNKNOWN 被误当成"还在跑"。
        val queue = listOf(JobSummary(id = "job-1", state = JobState.PENDING))
        val result = verdict("job-1", queue = queue)
        assertEquals(McpJobState.Phase.QUEUED, result.phase)
        assertEquals(1, result.position)
    }

    @Test
    fun runningIsDistinctFromQueued() {
        val queue = listOf(
            JobSummary(id = "other", state = JobState.RUNNING),
            JobSummary(id = "job-1", state = JobState.RUNNING),
        )
        val result = verdict("job-1", queue = queue)
        assertEquals(McpJobState.Phase.RUNNING, result.phase)
        assertEquals(2, result.position)
    }

    @Test
    fun doneWhenHistoryHasOutputs() {
        val history = JSONObject().put("job-1", JSONObject())
        assertEquals(McpJobState.Phase.DONE, verdict("job-1", queue = emptyList(), history = history, hasOutputs = true).phase)
    }

    @Test
    fun failedWhenHistoryEntryHasNoOutputs() {
        // 进了 history 却没输出 = 执行报错，不能一直等。
        val history = JSONObject().put("job-1", JSONObject())
        assertEquals(McpJobState.Phase.FAILED, verdict("job-1", queue = emptyList(), history = history).phase)
    }

    @Test
    fun unknownWhenAbsentEverywhere() {
        assertEquals(McpJobState.Phase.UNKNOWN, verdict("job-1", queue = emptyList(), history = JSONObject()).phase)
        // 队列读不到（网络问题）时不判失败——那是网络状态，不是任务状态。
        assertEquals(McpJobState.Phase.UNKNOWN, verdict("job-1", queue = null, history = null).phase)
    }

    @Test
    fun blankIdIsUnknown() {
        assertEquals(McpJobState.Phase.UNKNOWN, verdict("").phase)
    }

    @Test
    fun phaseLabelsAreStableForTheModel() {
        // 模型读的是 label 字符串：queued/running/done/failed 不能随意改，
        // 否则 Skill 与模型侧的判断会一起失效。
        assertEquals("queued", McpJobState.Phase.QUEUED.label)
        assertEquals("running", McpJobState.Phase.RUNNING.label)
        assertEquals("done", McpJobState.Phase.DONE.label)
        assertEquals("failed", McpJobState.Phase.FAILED.label)
    }
}
