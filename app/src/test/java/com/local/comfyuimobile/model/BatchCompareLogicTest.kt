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
}
