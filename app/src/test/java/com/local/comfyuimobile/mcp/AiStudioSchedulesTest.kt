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

    private fun coded(name: String, code: Int) = AiStudioSchedule(
        scheduleName = name,
        label = name,
        available = code == 1,
        availabilityCode = code,
    )

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

    @Test
    fun acceptsDisplayLabelAsInput() {
        // AI 很容易把界面上看到的名字（"V100 16GB"）传进来，直接报错会让它多绕一圈。
        // 能唯一对上就接受。
        val chosen = AiStudioSchedules.choose(options, "V100 16GB")
        assertEquals("V100", chosen?.scheduleName)
    }

    @Test
    fun ambiguousLabelAsksForClarification() {
        // 同一个显示名对应多档时**绝不自己挑**：16GB 与 32GB 价格不同，
        // 猜错等于替用户花钱。要报错让 AI 用 scheduleName 澄清。
        val ambiguous = listOf(
            schedule("V100_16", "V100"),
            schedule("V100_32", "V100"),
        )
        val error = runCatching { AiStudioSchedules.choose(ambiguous, "V100") }.exceptionOrNull()
        assertTrue("应报错而不是猜", error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("多个候选"))
        // 报错里要列出候选的 scheduleName，它才能重试。
        assertTrue(error.message!!.contains("V100_16"))
        assertTrue(error.message!!.contains("V100_32"))
    }

    @Test
    fun unknownNameErrorListsBothKeysAndLabels() {
        val error = runCatching { AiStudioSchedules.choose(options, "H100") }.exceptionOrNull()
        // 列 scheduleName 的同时也要带 label，否则 AI 无法把"界面上看到的"对上。
        assertTrue(error!!.message!!.contains("V100"))
        assertTrue(error.message!!.contains("V100 16GB"))
    }

    @Test
    fun projectStateDistinguishesFourStates() {
        // 清单 §3.1 要求 gpu_status 能区分这四种——因为下一步完全不同。
        assertEquals(
            AiStudioSchedules.ProjectState.COOKIE_EXPIRED,
            AiStudioSchedules.projectState(loginExpired = true, running = true, environmentReady = true, starting = false),
        )
        assertEquals(
            AiStudioSchedules.ProjectState.RUNNING,
            AiStudioSchedules.projectState(loginExpired = false, running = true, environmentReady = true, starting = false),
        )
        // running=true 但环境没就绪：那只是**受理回执**，实际还在分配。
        assertEquals(
            AiStudioSchedules.ProjectState.SUBMITTING,
            AiStudioSchedules.projectState(loginExpired = false, running = true, environmentReady = false, starting = false),
        )
        assertEquals(
            AiStudioSchedules.ProjectState.STOPPED,
            AiStudioSchedules.projectState(loginExpired = false, running = false, environmentReady = false, starting = false),
        )
    }

    @Test
    fun everyProjectStateHasActionableText() {
        // 只说"查不到"，AI 只能瞎试；每种状态都要带下一步。
        AiStudioSchedules.ProjectState.entries.forEach { state ->
            val text = AiStudioSchedules.describeState(state)
            assertTrue("$state 的文案太短：$text", text.length > 10)
        }
    }

    // ===== v0.3.7（文档 §3.5）：区分状态码 2 与 3 =====

    @Test
    fun code3MeansOutOfQuotaAndSuggestsLowerTier() {
        // 余额用完 → 换便宜的档位立刻能跑。这里必须给出这个动作。
        val note = AiStudioSchedules.unavailableNote(coded("A100", 3))
        assertTrue("应提到算力不足：$note", note.contains("算力不足"))
        assertTrue("应建议换低档位：$note", note.contains("低档"))
    }

    @Test
    fun code2MeansUnavailableAndSaysWaitingIsUseless() {
        // 无货/下架 → 等也没用。若与 3 混成一句，AI 会一直重试一个永远不会好的档。
        val note = AiStudioSchedules.unavailableNote(coded("V100", 2))
        assertTrue("应提到无货：$note", note.contains("无货"))
        assertTrue("应说等也没用：$note", note.contains("等也没用"))
    }

    @Test
    fun availableScheduleHasNoNote() {
        assertEquals("", AiStudioSchedules.unavailableNote(coded("V100", 1)))
    }

    @Test
    fun unknownCodeStillSaysUnavailable() {
        // 拿不到原码也要说"不可用"，只是不细分原因（不编）。
        val note = AiStudioSchedules.unavailableNote(schedule("X", available = false))
        assertTrue(note.contains("不可用"))
        assertTrue("不该编造原因：$note", !note.contains("算力不足") && !note.contains("无货"))
    }
}
