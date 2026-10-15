package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.model.AiStudioSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实际可跑时长折算单测（v0.3.8，P0-4）。
 *
 * 单位依据（官网档位弹窗截图）：
 * ```
 * 算力卡余额 8.0 点  →  选中环境可用时间 4.0 小时
 * 8.0 ÷ 4.0 = 2.0 点/小时  ≡  costPerHour "200" ÷ 100  ✓
 * ```
 * v0.3.7 因为单位存疑刻意没做折算；现在确认了 `/100` 是对的。
 */
class AiStudioQuotaRunnableTest {

    // 8 点余额 = 480 分钟；V100 16G 的 costPerHour = 200（= 2 点/小时）→ 4 小时。
    private val balance480 = 480.0

    @Test
    fun matchesOfficialPopupExample() {
        // 官网弹窗：8.0 点余额 + V100 16G（costPerHour=200）→ 4.0 小时。
        val minutes = AiStudioQuota.runnableMinutes(balance480, costPerHour = 200.0, poolMinutes = null)
        assertEquals(240.0, minutes!!, 0.001)
        assertEquals("约 4 小时（受算力卡余额限制）", AiStudioQuota.runnableLabel(balance480, 200.0, null))
    }

    @Test
    fun takesSmallerOfBalanceAndPool() {
        // 余额够跑 240 分钟，但该档周配额只剩 100 分钟 → 取小的那个。
        val minutes = AiStudioQuota.runnableMinutes(balance480, costPerHour = 200.0, poolMinutes = 100.0)
        assertEquals(100.0, minutes!!, 0.001)
        assertTrue(AiStudioQuota.runnableLabel(balance480, 200.0, 100.0)!!.contains("周配额限制"))
    }

    @Test
    fun poolIsIgnoredWhenUnknown() {
        // 周配额未知（null）**不能**当成 0——那会把可跑时长算成 0，
        // 用户会以为一点都跑不了。
        val minutes = AiStudioQuota.runnableMinutes(balance480, 200.0, null)
        assertEquals(240.0, minutes!!, 0.001)
    }

    @Test
    fun returnsNullWhenInputsUnusable() {
        // 任一类键输入不可信就返回 null（**不编数字**）——这是本项目的硬规矩。
        assertNull(AiStudioQuota.runnableMinutes(null, 200.0, null))
        assertNull(AiStudioQuota.runnableMinutes(0.0, 200.0, null))
        assertNull(AiStudioQuota.runnableMinutes(balance480, null, null))
        assertNull(AiStudioQuota.runnableMinutes(balance480, 0.0, null))
    }

    @Test
    fun labelIsNullWhenNotComputable() {
        assertNull(AiStudioQuota.runnableLabel(null, 200.0, null))
        assertNull(AiStudioQuota.runnableLabel(balance480, null, null))
    }

    @Test
    fun cheaperTierRunsLonger() {
        // 同一笔余额，便宜档跑得更久——这正是\"换档位而不是放弃\"的依据。
        val v100_16 = AiStudioQuota.runnableMinutes(balance480, costPerHour = 200.0, poolMinutes = null)!!
        val v100_32 = AiStudioQuota.runnableMinutes(balance480, costPerHour = 400.0, poolMinutes = null)!!
        assertTrue("16G 应比 32G 跑得久", v100_16 > v100_32)
        assertEquals(v100_16 / 2, v100_32, 0.001)
    }

    // ===== describe 带上折算 =====

    @Test
    fun describeIncludesRunnableAndPool() {
        val schedule = AiStudioSchedule(
            scheduleName = "V100",
            label = "V100 16GB",
            costPerHour = 200.0,
            weekQuotaType = "V100",
        )
        val text = AiStudioSchedules.describe(schedule, pools = mapOf("V100" to 1212.0), balanceMinutes = 480.0)
        assertTrue("应有周剩余：$text", text.contains("1212"))
        assertTrue("应有实际可跑：$text", text.contains("实际可跑"))
        assertTrue("应标明受余额限制：$text", text.contains("余额限制"))
    }

    @Test
    fun describeOmitsRunnableWhenBalanceUnknown() {
        // 余额没读到就不给\"实际可跑\"——不编。
        val schedule = AiStudioSchedule(scheduleName = "V100", label = "V100", costPerHour = 200.0, weekQuotaType = "V100")
        val text = AiStudioSchedules.describe(schedule, pools = mapOf("V100" to 1212.0), balanceMinutes = null)
        assertTrue(!text.contains("实际可跑"))
    }
}
