package com.local.comfyuimobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GPU 会话记录编解码单测（v0.3.7，P0-3）。
 *
 * 与时长计算分开测：这里只管「存的东西能不能原样读回来」，后者管「能不能算」。
 * 两者出错的表现不同——前者是时长永远不显示（静默失效），后者是账单算错。
 */
class GpuSessionStartTest {

    @Test
    fun roundTrips() {
        val encoded = GpuSessionStart.encode(1_700_000_000_000L, "10707054", "resourceCardVGpuSchedule")
        val parsed = GpuSessionStart.parse(encoded)
        assertEquals(1_700_000_000_000L, parsed?.startedAt)
        assertEquals("10707054", parsed?.projectId)
        assertEquals("resourceCardVGpuSchedule", parsed?.scheduleName)
    }

    @Test
    fun blankYieldsNull() {
        assertNull(GpuSessionStart.parse(""))
        assertNull(GpuSessionStart.parse("   "))
    }

    @Test
    fun malformedYieldsNullInsteadOfGuessing() {
        // 少了字段 / 时刻不是数字 → 就是未知，不能编。
        assertNull(GpuSessionStart.parse("1700000000000|10707054"))
        assertNull(GpuSessionStart.parse("not-a-number|p|s"))
        assertNull(GpuSessionStart.parse("0|p|s"))
    }

    @Test
    fun keepsScheduleNameContainingPipe() {
        // 防回归：档位名理论上不含 |，但万一含了也不能丢后半截。
        val parsed = GpuSessionStart.parse(GpuSessionStart.encode(5L, "p", "a|b"))
        assertEquals("a|b", parsed?.scheduleName)
    }
}
