package com.local.comfyuimobile.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GPU 会话时长单测（v0.3.7，P0-3）。
 *
 * 这一条的核心不是"算得准"，而是**估不出来时必须返回 null**：
 * 上一轮我拒绝用 `updatedAt` 推算时长（那不是起始时间），理由就是"编一个账单比
 * 不给更糟"。这些测试把那个拒绝钉住。
 */
class McpSessionClockTest {

    private val minute = 60_000L

    @Test
    fun computesMinutesFromRealStartTime() {
        val start = 1_000_000L
        assertEquals(30L, McpSessionClock.runningMinutes(start, sameTarget = true, now = start + 30 * minute))
    }

    @Test
    fun returnsNullWhenStartUnknown() {
        // 老数据 / 从没通过本 App 启动过：就是不知道，不能编。
        assertNull(McpSessionClock.runningMinutes(null, sameTarget = true, now = 999_999L))
        assertNull(McpSessionClock.runningMinutes(0L, sameTarget = true, now = 999_999L))
    }

    @Test
    fun returnsNullWhenTargetMismatch() {
        // 记录是别的项目的 → 不能拿来算"这个项目跑了多久"。
        assertNull(McpSessionClock.runningMinutes(1_000L, sameTarget = false, now = 500_000L))
    }

    @Test
    fun returnsNullOnClockGoingBackwards() {
        // 时钟回拨等异常：不能报一个负数或巨大值。
        assertNull(McpSessionClock.runningMinutes(5_000L, sameTarget = true, now = 1_000L))
    }

    @Test
    fun floorsToWholeMinutes() {
        // 宁可少报一分钟，也不要"0.02 分钟"这类噪声。
        assertEquals(0L, McpSessionClock.runningMinutes(0L + 1, sameTarget = true, now = 1 + 59_999L))
        assertEquals(1L, McpSessionClock.runningMinutes(1L, sameTarget = true, now = 1 + minute))
    }

    @Test
    fun onlyReportsWhenActuallyRunning() {
        assertTrue(McpSessionClock.shouldReport(running = true, minutes = 5L))
        // 项目没在跑时给时长，用户会以为还在扣钱。
        assertFalse(McpSessionClock.shouldReport(running = false, minutes = 5L))
        assertFalse(McpSessionClock.shouldReport(running = true, minutes = null))
    }

    @Test
    fun estimatesCostFromRate() {
        // 60 分钟 × 1.0/小时 = 1.0
        assertEquals(1.0, McpSessionClock.estimateCost(60L, 1.0)!!, 0.0001)
        // 30 分钟 × 0.5/小时 = 0.25
        assertEquals(0.25, McpSessionClock.estimateCost(30L, 0.5)!!, 0.0001)
    }

    @Test
    fun costIsNullWhenRateMissing() {
        assertNull(McpSessionClock.estimateCost(60L, null))
        assertNull(McpSessionClock.estimateCost(60L, 0.0))
        assertNull(McpSessionClock.estimateCost(null, 1.0))
    }
}
