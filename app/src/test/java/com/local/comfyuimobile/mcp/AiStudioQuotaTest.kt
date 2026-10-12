package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.model.AiStudioSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档位↔配额池关联单测（v0.3.8，P0-1）。
 *
 * 修的是「每档都印同一个剩余小时数」：真机实测 CPU / DCU / V100 全显示 20.2 小时，
 * 而紧挨着的提示语写着「换成不同显卡能跑的小时数不一样」——数字与提示自相矛盾。
 *
 * 根因是 `weekQuotaType` 只赋值、从未被读，于是没人把档位与它自己的配额池接起来。
 */
class AiStudioQuotaTest {

    private fun sched(name: String, quotaType: String, available: Boolean = true) = AiStudioSchedule(
        scheduleName = name,
        label = name,
        available = available,
        weekQuotaType = quotaType,
    )

    @Test
    fun matchesScheduleToItsOwnPool() {
        val pools = mapOf("V100" to 1212.0, "A100" to 240.0)
        val pool = AiStudioQuota.poolFor(sched("resourceCardVGpuSchedule", "V100"), pools)
        assertEquals(1212.0, pool!!.remainingMinutes, 0.001)
        assertEquals("V100", pool.type)
    }

    @Test
    fun doesNotBorrowAnotherPoolWhenTypeMissing() {
        // 拿不到 weekQuotaType 时必须是 null —— **不能**随手拿一个池顶替，
        // 那正是以前"每档都印同一个数"的病根。
        assertNull(AiStudioQuota.poolFor(sched("X", ""), mapOf("V100" to 100.0)))
    }

    @Test
    fun typeMatchIsCaseInsensitive() {
        // 平台同一份数据在不同接口里大小写不稳（本项目已多次遇到）。
        val pool = AiStudioQuota.poolFor(sched("X", "v100"), mapOf("V100" to 500.0))
        assertEquals(500.0, pool!!.remainingMinutes, 0.001)
    }

    @Test
    fun describePoolReturnsNullWhenUnmatched() {
        assertNull(AiStudioQuota.describePool(sched("X", ""), mapOf("V100" to 100.0)))
        assertNull(AiStudioQuota.describePool(sched("X", "A100"), mapOf("V100" to 100.0)))
    }

    @Test
    fun describePoolNamesThePool() {
        // 必须写明归哪个池，否则用户不知道这个数是哪来的。
        val text = AiStudioQuota.describePool(sched("X", "V100"), mapOf("V100" to 1212.0))
        assertTrue(text!!, text.contains("1212"))
        assertTrue(text, text.contains("V100"))
    }

    @Test
    fun unmatchedPoolsAreReportedSoNothingDisappears() {
        // V100 有档位引用、A100 没有 → A100 应出现在"未匹中"里，信息不凭空消失。
        val schedules = listOf(sched("V100", "V100"))
        val pools = mapOf("V100" to 100.0, "A100" to 240.0)
        val unmatched = AiStudioQuota.unmatchedPools(schedules, pools)
        assertEquals(listOf("A100"), unmatched.map { it.type })
    }

    @Test
    fun allPoolsUnmatchedWhenNoScheduleCarriesType() {
        // 平台改版拿不到 weekQuotaType 时，调用方据此退回"只罗列池子"的旧行为。
        val unmatched = AiStudioQuota.unmatchedPools(
            listOf(sched("V100", "")),
            mapOf("V100" to 100.0, "A100" to 240.0),
        )
        assertEquals(setOf("V100", "A100"), unmatched.map { it.type }.toSet())
    }
}
