package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 每日任务门闩单测（v0.2.92）。
 *
 * 对应真机事故：只用"任务在跑吗"挡重复，挡不住 runDailyTasksFor 末尾触发的
 * 嵌套刷新（它写偏好 → 偏好推送 → 又跑一轮），一秒内几十次请求触发平台风控
 * 8407，导致 GPU 档位读不到、只能默认档位启动。
 */
class DailyTaskGateTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    @Test
    fun firstCallClaimsAllAccounts() {
        val guard = mutableMapOf<String, String>()
        val claimed = DailyTaskGate.claim(listOf("a", "b"), guard, at(2026, 10, 6))
        assertEquals(listOf("a", "b"), claimed)
    }

    @Test
    fun secondCallSameDayClaimsNothing() {
        // 核心回归：同一天重复调用必须全部挡下——这正是断掉事件循环的那一步。
        val guard = mutableMapOf<String, String>()
        DailyTaskGate.claim(listOf("a"), guard, at(2026, 10, 6, 1))
        assertTrue(DailyTaskGate.claim(listOf("a"), guard, at(2026, 10, 6, 23)).isEmpty())
    }

    @Test
    fun nextDayClaimsAgain() {
        val guard = mutableMapOf<String, String>()
        DailyTaskGate.claim(listOf("a"), guard, at(2026, 10, 6))
        assertEquals(listOf("a"), DailyTaskGate.claim(listOf("a"), guard, at(2026, 10, 7)))
    }

    @Test
    fun newAccountIsNotBlockedByAnotherAccountsRun() {
        // 门闩按账号隔离：给 a 记过，不能顺带把 b 也挡掉。
        val guard = mutableMapOf<String, String>()
        DailyTaskGate.claim(listOf("a"), guard, at(2026, 10, 6))
        assertEquals(listOf("b"), DailyTaskGate.claim(listOf("a", "b"), guard, at(2026, 10, 6)))
    }

    @Test
    fun hasRunTodayReflectsGuard() {
        val guard = mutableMapOf<String, String>()
        val now = at(2026, 10, 6)
        assertFalse(DailyTaskGate.hasRunToday("a", guard, now))
        DailyTaskGate.claim(listOf("a"), guard, now)
        assertTrue(DailyTaskGate.hasRunToday("a", guard, now))
        assertFalse(DailyTaskGate.hasRunToday("a", guard, at(2026, 10, 7)))
    }

    @Test
    fun dayKeyIsStableWithinSameDay() {
        assertEquals(DailyTaskGate.dayKey(at(2026, 10, 6, 0)), DailyTaskGate.dayKey(at(2026, 10, 6, 23)))
    }
}
