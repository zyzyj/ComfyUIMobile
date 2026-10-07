package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.model.AiStudioSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GPU 档位匹配单测（v0.2.97）。
 *
 * 这层挡的是**静默降级**：`startProject` 把档位名原样透传给平台，传错时平台不报错、
 * 而是按默认档启动——用户以为选了 A100，开出来的却是 V100，账单照扣。开出来的卡
 * 与预期不符时，从日志里是看不出来的（平台没有错误），所以只能靠提交前的匹配挡住。
 */
class AiStudioSchedulesTest {

    private fun schedule(name: String, label: String = "", available: Boolean = true) =
        AiStudioSchedule(scheduleName = name, label = label, available = available)

    private val options = listOf(
        schedule("V100", "V100 16GB"),
        schedule("A100", "A100 40GB"),
        schedule("A100_80", "A100 80GB"),
    )

    @Test
    fun exactMatchWinsOverPrefix() {
        // "A100" 必须只能命中原生的 "A100"，不能命中 "A100_80"。
        // 用 contains 之类的模糊匹配就会在这里出错，且价格差一倍。
        val chosen = AiStudioSchedules.choose(options, "A100")
        assertEquals("A100", chosen?.scheduleName)
    }

    @Test
    fun matchIsCaseInsensitiveAndTrimmed() {
        assertEquals("V100", AiStudioSchedules.choose(options, "  v100 ")?.scheduleName)
    }

    @Test
    fun blankUsesFirstOption() {
        // 没指定档位时的语义是"用列表第一项"，与界面默认选中第一项一致。
        assertEquals("V100", AiStudioSchedules.choose(options, null)?.scheduleName)
        assertEquals("V100", AiStudioSchedules.choose(options, "   ")?.scheduleName)
    }

    @Test
    fun unknownNameThrowsWithAvailableList() {
        val error = runCatching { AiStudioSchedules.choose(options, "H100") }.exceptionOrNull()
        assertTrue("应抛异常而不是静默降级", error is IllegalArgumentException)
        // 报错必须带可选项，否则模型只能盲猜下一轮。
        assertTrue(error!!.message!!.contains("V100"))
        assertTrue(error.message!!.contains("A100"))
    }

    @Test
    fun emptyOptionsReturnNull() {
        // 读不到档位时返回 null，由调用方决定怎么报——不能编一个名字提交上去。
        assertNull(AiStudioSchedules.choose(emptyList(), "V100"))
        assertNull(AiStudioSchedules.choose(emptyList(), null))
    }

    @Test
    fun describeCarriesScheduleNameAndCost() {
        // scheduleName 是真正要传给平台的值，展示里必须带上；否则用户
        // 复制了 label（"V100 16GB"）去启动就会匹配失败。
        val text = AiStudioSchedules.describe(
            AiStudioSchedule(
                scheduleName = "V100",
                label = "V100 16GB",
                costPerHour = 380.0,
                weekRemainingMinutes = 120.0,
            ),
        )
        assertTrue(text, text.contains("schedule=V100"))
        assertTrue(text, text.contains("3.8 算力卡/小时"))
        assertTrue(text, text.contains("120 分钟"))
    }

    @Test
    fun describeMarksUnavailable() {
        val text = AiStudioSchedules.describe(schedule("V100", "V100 16GB", available = false))
        assertTrue(text, text.contains("当前不可用"))
    }
}
