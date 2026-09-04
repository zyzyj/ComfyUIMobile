package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.83 批量 LoRA 对比：纯逻辑单测。
 * 重点覆盖启发式标黄（宁可漏报不可误报）与候选截断/自动暂停阈值。
 */
class BatchCompareLogicTest {

    // ===== suspectIncompatible：家族识别与词边界 =====

    @Test
    fun flagsCrossFamilyLoraAgainstAnimaCheckpoint() {
        // anima（qwen 家族）checkpoint 配 SDXL LoRA → 疑似
        assertTrue(BatchCompareLogic.suspectIncompatible("anima_base_v1.safetensors", "sdxl_detail_lora.safetensors"))
    }

    @Test
    fun treatsAnimaAndQwenAsSameFamily() {
        // anima 是 Qwen 图像架构：qwen LoRA 与 anima checkpoint 同家族，不标黄
        assertFalse(BatchCompareLogic.suspectIncompatible("anima_base_v1.safetensors", "qwen_lighting_lora.safetensors"))
    }

    @Test
    fun treatsPonyAsSdxfFamily() {
        assertFalse(BatchCompareLogic.suspectIncompatible("sdxl_base_1.0.safetensors", "pony_style_v2.safetensors"))
        // pony LoRA 配 SD1.5 checkpoint → 疑似（checkpoint 名带 v1-5 标识）
        assertTrue(BatchCompareLogic.suspectIncompatible("dreamshaper_8_v1-5.safetensors", "pony_style_v2.safetensors"))
    }

    @Test
    fun flagsFluxLoraAgainstSd15Checkpoint() {
        assertTrue(BatchCompareLogic.suspectIncompatible("v1-5-pruned-emaonly.safetensors", "flux_realism.safetensors"))
    }

    @Test
    fun sameFamilyNeverFlagged() {
        assertFalse(BatchCompareLogic.suspectIncompatible("sdxl_base.safetensors", "xl_sharp_lora.safetensors"))
        assertFalse(BatchCompareLogic.suspectIncompatible("sd_xl_base.safetensors", "sdxl_skin_lora.safetensors"))
        assertFalse(BatchCompareLogic.suspectIncompatible("sd15_model.safetensors", "sd15_face_lora.safetensors"))
    }

    @Test
    fun missingCheckpointHintNeverFlags() {
        assertFalse(BatchCompareLogic.suspectIncompatible(null, "sdxl_lora.safetensors"))
        assertFalse(BatchCompareLogic.suspectIncompatible("", "sdxl_lora.safetensors"))
    }

    @Test
    fun unrecognizableNamesNeverFlags() {
        // checkpoint 认不出家族
        assertFalse(BatchCompareLogic.suspectIncompatible("mystery_model_v3.safetensors", "sdxl_lora.safetensors"))
        // LoRA 认不出家族（大量 Civitai 文件名不带基模型词）
        assertFalse(BatchCompareLogic.suspectIncompatible("sdxl_base.safetensors", "beautiful_style_v2.safetensors"))
    }

    @Test
    fun wordBoundaryPreventsFalseKeywordHits() {
        // "xl" 不能命中 "exlsx"（exlsx 识别不出家族 → 不标）
        assertFalse(BatchCompareLogic.suspectIncompatible("exlsx_base.safetensors", "sdxl_lora.safetensors"))
        // 合法 "xl" 命中：xl_base（sdxl 家族）对 flux_lora（flux 家族）→ 标黄
        assertTrue(BatchCompareLogic.suspectIncompatible("xl_base.safetensors", "flux_lora.safetensors"))
        // "anima" 不能命中 "animation"（animation_engine_v2 识别不出家族 → 不标）
        assertFalse(BatchCompareLogic.suspectIncompatible("animation_engine_v2.safetensors", "sdxl_lora.safetensors"))
        // 下划线/横线算边界："sd-xl_base" 命中 sdxl 家族 → 对 sd15 LoRA 标黄
        assertTrue(BatchCompareLogic.suspectIncompatible("sd-xl_base.safetensors", "sd15_face_lora.safetensors"))
    }

    // ===== truncate：去空 / 去重 / 上限 =====

    @Test
    fun truncateDropsBlankAndDuplicates() {
        val result = BatchCompareLogic.truncate(listOf("a.safetensors", "", "b.sft", "a.safetensors", "  "))
        assertEquals(listOf("a.safetensors", "b.sft"), result)
    }

    @Test
    fun truncateEnforcesCandidateCap() {
        val many = (1..50).map { "lora_$it.safetensors" }
        assertEquals(BatchCompareLogic.MAX_CANDIDATES, BatchCompareLogic.truncate(many).size)
    }

    // ===== 自动暂停阈值 =====

    @Test
    fun autoPauseTriggersOnThirdConsecutiveFailure() {
        assertFalse(BatchCompareLogic.shouldAutoPause(0))
        assertFalse(BatchCompareLogic.shouldAutoPause(1))
        assertFalse(BatchCompareLogic.shouldAutoPause(2))
        assertTrue(BatchCompareLogic.shouldAutoPause(3))
        assertTrue(BatchCompareLogic.shouldAutoPause(4))
    }

    // ===== 耗时预估 =====

    @Test
    fun estimateMinutesRoundsUp() {
        // 10 张 × 108 秒 = 1080 秒 = 18 分钟整
        assertEquals(18, BatchCompareLogic.estimateMinutes(10, 108_000L))
        // 7 张 × 108 秒 = 756 秒 → 12.6 → 13（向上取整）
        assertEquals(13, BatchCompareLogic.estimateMinutes(7, 108_000L))
        assertEquals(1, BatchCompareLogic.estimateMinutes(1, 30_000L))
    }

    @Test
    fun estimateMinutesReturnsNullWithoutHistory() {
        assertNull(BatchCompareLogic.estimateMinutes(10, null))
        assertNull(BatchCompareLogic.estimateMinutes(0, 108_000L))
        assertNull(BatchCompareLogic.estimateMinutes(10, 0L))
    }

    // ===== BatchRun 计数 =====

    @Test
    fun batchRunCountsIncludeCurrentItem() {
        val run = BatchRun(
            id = "b1",
            workflowPath = "w.json",
            workflowName = "w",
            loraFieldKey = "k",
            loraNodeTitle = "加载LoRA",
            seed = "123",
            originalLoraValue = "orig.safetensors",
            pending = listOf("c.safetensors", "d.safetensors"),
            current = "e.safetensors",
            items = listOf(
                BatchItemResult("a.safetensors", success = true),
                BatchItemResult("b.safetensors", success = false, message = "失败"),
            ),
        )
        assertEquals(5, run.total)
        assertEquals(2, run.finished)
        assertEquals(1, run.successCount)
        assertEquals(1, run.failedCount)
    }

    // ===== v0.1.84 状态转移（v0.1.83 现场事故的回归锁） =====

    private fun newRun(pending: List<String>, planned: Int = pending.size) = BatchRun(
        id = "t", workflowPath = "w.json", workflowName = "w",
        loraFieldKey = "k", loraNodeTitle = "加载LoRA",
        seed = "1", originalLoraValue = "orig.safetensors",
        pending = pending, plannedTotal = planned,
    )

    @Test
    fun advanceQueueShiftsPendingIntoCurrent() {
        val run = BatchCompareLogic.advanceQueue(newRun(listOf("a.safetensors", "b.safetensors")))!!
        assertEquals("a.safetensors", run.current)
        assertEquals(listOf("b.safetensors"), run.pending)
        assertEquals(2, run.total) // total 守恒：pending 2 → current 1 + pending 1
    }

    @Test
    fun advanceQueueReturnsNullWhenDrained() {
        assertNull(BatchCompareLogic.advanceQueue(newRun(emptyList())))
    }

    @Test
    fun fullCycleRunsEachCandidateExactlyOnce() {
        // v0.1.83 的现场 bug：取了 first() 却从不 drop → 同一个 LoRA 无限重跑。
        // 这里锁死"每个候选恰好跑一次、跑完队列即空"的不变量。
        var run = newRun(listOf("a.safetensors", "b.safetensors", "c.safetensors"))
        val executed = mutableListOf<String>()
        var rounds = 0
        while (true) {
            val advanced = BatchCompareLogic.advanceQueue(run) ?: break
            executed.add(advanced.current!!)
            run = BatchCompareLogic.completeItem(advanced, BatchItemResult(advanced.current!!, success = true))
            rounds++
            assertTrue("队列未减少，疑似 pending 泄漏", rounds <= 5)
        }
        assertEquals(listOf("a.safetensors", "b.safetensors", "c.safetensors"), executed)
        assertEquals(3, run.items.size)
        assertTrue(run.pending.isEmpty())
        assertNull(run.current)
        assertEquals(3, run.total)
    }

    @Test
    fun reachedPlannedCapStopsRunawayQueue() {
        // 防呆上限：即使 pending 永不减少（bug 行为），完成数达到计划总数也要停。
        var run = newRun(listOf("a", "b", "c"), planned = 3)
        var guard = 0
        while (!BatchCompareLogic.reachedPlannedCap(run)) {
            // 模拟 v0.1.83 的泄漏行为：只加 items，pending 原封不动
            run = run.copy(items = run.items + BatchItemResult("a", success = true))
            guard++
            assertTrue("防呆上限失效，仍在无限跑", guard <= 5)
        }
        assertEquals(3, run.items.size)
    }

    @Test
    fun plannedCapDisabledWhenZero() {
        // plannedTotal=0（旧数据/未设置）不触发防呆，走正常队列空判定
        val run = newRun(listOf("a")).copy(plannedTotal = 0)
        assertFalse(BatchCompareLogic.reachedPlannedCap(run))
    }
}
