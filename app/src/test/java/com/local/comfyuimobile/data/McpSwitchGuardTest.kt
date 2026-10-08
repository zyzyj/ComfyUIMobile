package com.local.comfyuimobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 开关写入序号守卫单测（v0.3.5，P0-3）。
 *
 * 场景：用户打开开关（写 true，seq=T2），而**上一次**服务停止的落盘（写 false，
 * seq=T1）迟到了。没有序号守卫时，T1 会把 T2 的 true 盖成 false——
 * 就是用户看到的「打开就关上」。
 */
class McpSwitchGuardTest {

    @Test
    fun staleWriteIsDiscarded() {
        // 迟到的一次旧写入（T1）不能覆盖已记录的更新的 T2。
        assertTrue(McpSwitchGuard.shouldDiscard(seq = 1000L, recorded = 2000L))
    }

    @Test
    fun newerWriteIsApplied() {
        assertFalse(McpSwitchGuard.shouldDiscard(seq = 3000L, recorded = 2000L))
        assertTrue(McpSwitchGuard.shouldApply(seq = 3000L, recorded = 2000L))
    }

    @Test
    fun equalSeqIsApplied() {
        // 同一时刻的写入视为有效（不应被自己丢弃）。
        assertTrue(McpSwitchGuard.shouldApply(seq = 2000L, recorded = 2000L))
        assertFalse(McpSwitchGuard.shouldDiscard(seq = 2000L, recorded = 2000L))
    }

    @Test
    fun firstWriteAlwaysApplies() {
        // 从未写过（recorded=0）时的首次写入。
        assertTrue(McpSwitchGuard.shouldApply(seq = 1L, recorded = 0L))
    }

    @Test
    fun theActualBugScenario() {
        // 完整复现：停止的余波（false @T1）迟到，不能盖掉开启（true @T2）。
        val openSeq = 2_000L
        val lateStopSeq = 1_000L
        // 开启先落盘：recorded 变成 openSeq
        assertTrue(McpSwitchGuard.shouldApply(openSeq, recorded = 0L))
        // 迟到的停止写入被拦下
        assertTrue(McpSwitchGuard.shouldDiscard(lateStopSeq, recorded = openSeq))
    }
}
