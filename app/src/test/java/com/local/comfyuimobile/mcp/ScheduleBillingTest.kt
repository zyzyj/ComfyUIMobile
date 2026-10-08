package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.model.AiStudioSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档位降级说明与账单展示单测（v0.3.4，清单 §1.1/§1.2/§1.3）。
 *
 * 这三条都**直接等于钱**：
 * - 回落不明说 → 用户以为还是上次的价，实际扣得更多（静默降级）；
 * - 不引导「按档位独立」→ AI 告诉用户「没算力了」，而其他档还剩几十小时；
 * - 不给消耗速率 → AI 不知道 GPU 开着就在扣。
 */
class ScheduleBillingTest {

    // ===== §1.1 回落必须明说 =====

    @Test
    fun fallbackMustBeExplained() {
        val note = AiStudioSchedules.resumeNote(
            explicitlyAsked = false,
            remembered = "V100",
            fellBack = true,
            chosenName = "A100 40GB",
        )
        assertTrue("必须提到原档位：$note", note.contains("V100"))
        assertTrue("必须提到换成了什么：$note", note.contains("A100 40GB"))
        assertTrue("必须标记不可选：$note", note.contains("不可选"))
        assertTrue("必须给出下一步：$note", note.contains("list_gpu_options"))
    }

    @Test
    fun rememberedStillUsableSaysSo() {
        val note = AiStudioSchedules.resumeNote(false, "V100", fellBack = false, chosenName = "V100 16GB")
        assertTrue(note, note.contains("沿用"))
    }

    @Test
    fun explicitlyAskedDoesNotExplain() {
        // AI 显式传了 schedule：档位就是它要的，不需要任何解释。
        assertEquals("", AiStudioSchedules.resumeNote(true, "V100", fellBack = true, chosenName = "A100"))
        assertEquals("", AiStudioSchedules.resumeNote(true, null, fellBack = false, chosenName = "V100"))
    }

    @Test
    fun firstEverUseHasNoNote() {
        // 从没用过（remembered=null）：没有"上次"可说。
        assertEquals("", AiStudioSchedules.resumeNote(false, null, fellBack = false, chosenName = "V100"))
    }

    // ===== §1.3 数值展示 =====

    @Test
    fun trimFormatsLikeUi() {
        // 与界面同一条规则：3.0→"3"、3.8→"3.8"，两处数据长得一样才不会互相怀疑。
        assertEquals("3", AiStudioSchedules.trim(3.0))
        assertEquals("3.8", AiStudioSchedules.trim(3.8))
        assertEquals("45.8", AiStudioSchedules.trim(45.8))
    }

    // ===== §1.2 可用性一览 =====

    @Test
    fun availabilityHintListsAllSchedules() {
        val schedules = listOf(
            AiStudioSchedule(scheduleName = "V100", label = "V100 16GB", available = false),
            AiStudioSchedule(scheduleName = "DCU", label = "DCU", available = true, weekRemainingMinutes = 3360.0),
        )
        val hint = AiStudioBridge.availabilityHint(schedules)
        assertTrue("应说明按档位独立：$hint", hint.contains("独立"))
        assertTrue("应列出 DCU 的剩余：$hint", hint.contains("3360"))
        assertTrue("应给出行动：$hint", hint.contains("schedule"))
    }

    @Test
    fun availabilityHintHandlesEmptyList() {
        val hint = AiStudioBridge.availabilityHint(emptyList())
        assertTrue(hint, hint.contains("读不到"))
    }
}
