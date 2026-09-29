package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.52 LoRA 强度矩阵：纯逻辑单测。
 *
 * 重点覆盖区间展开的边界（浮点误差、除不尽、非法输入、超档位）与「只改一个槽位」的
 * 控制变量不变量。
 */
class LoraStrengthMatrixTest {

    // ===== expandSteps：区间展开 =====

    @Test
    fun expandsInclusiveRangeWithDecimalStep() {
        val steps = LoraStrengthMatrix.expandSteps(0.1, 1.0, 0.1)
        assertEquals(10, steps.size)
        assertEquals(0.1, steps.first(), 1e-9)
        assertEquals(1.0, steps.last(), 1e-9)
        // 浮点累加会漂出 0.30000000000000004，这里必须精确
        assertTrue("0.3 应精确落在列表里：$steps", steps.contains(0.3))
        assertTrue("0.7 应精确落在列表里：$steps", steps.contains(0.7))
    }

    @Test
    fun singleValueWhenStartEqualsEnd() {
        assertEquals(listOf(0.5), LoraStrengthMatrix.expandSteps(0.5, 0.5, 0.1))
    }

    @Test
    fun appendsEndValueWhenStepDoesNotDivideEvenly() {
        // 0.0~1.0 间距 0.3 → 0.0 / 0.3 / 0.6 / 0.9，再补结束值 1.0
        val steps = LoraStrengthMatrix.expandSteps(0.0, 1.0, 0.3)
        assertEquals(listOf(0.0, 0.3, 0.6, 0.9, 1.0), steps)
    }

    @Test
    fun doesNotDuplicateEndValueWhenItLandsOnStep() {
        // 0.0~1.0 间距 0.5 → 0.0 / 0.5 / 1.0，结束值已在列表里，不应再补一次
        assertEquals(listOf(0.0, 0.5, 1.0), LoraStrengthMatrix.expandSteps(0.0, 1.0, 0.5))
    }

    @Test
    fun rejectsInvalidInputs() {
        assertTrue(LoraStrengthMatrix.expandSteps(1.0, 0.0, 0.1).isEmpty())   // 结束 < 起始
        assertTrue(LoraStrengthMatrix.expandSteps(0.0, 1.0, 0.0).isEmpty())   // 间距为 0
        assertTrue(LoraStrengthMatrix.expandSteps(0.0, 1.0, -0.1).isEmpty())  // 间距为负
        assertTrue(LoraStrengthMatrix.expandSteps(Double.NaN, 1.0, 0.1).isEmpty())
        assertTrue(LoraStrengthMatrix.expandSteps(0.0, Double.POSITIVE_INFINITY, 0.1).isEmpty())
    }

    @Test
    fun rejectsStepCountBeyondGuard() {
        // 0.1~1 间距 0.001 → 901 档，必须被护栏拒绝而不是生成 901 个任务
        assertTrue(LoraStrengthMatrix.expandSteps(0.1, 1.0, 0.001).isEmpty())
    }

    @Test
    fun allowsFineStepWithinGuard() {
        // 间距 0.01 在护栏内（91 档）：允许展开，由界面按 MAX_TASKS 拦上限
        val steps = LoraStrengthMatrix.expandSteps(0.1, 1.0, 0.01)
        assertEquals(91, steps.size)
    }

    // ===== buildTasks：控制变量 =====

    private fun slot(
        nodeId: String = "4",
        name: String = "lora_a.safetensors",
        clip: String? = "4::strength_clip",
        model: Double = 0.8,
        clipValue: Double = 0.8,
    ) = LoraStrengthSlot(
        nodeId = nodeId,
        nodeTitle = "加载LoRA",
        loraName = name,
        nameFieldKey = "$nodeId::lora_name",
        modelFieldKey = "$nodeId::strength_model",
        clipFieldKey = clip,
        currentModel = model,
        currentClip = clipValue,
    )

    @Test
    fun buildsOneTaskPerStrengthForTargetSlot() {
        val tasks = LoraStrengthMatrix.buildTasks(
            slots = listOf(slot()),
            targetSlotNodeId = "4",
            strengths = LoraStrengthMatrix.expandSteps(0.1, 1.0, 0.1),
            target = LoraStrengthTarget.MODEL,
        )
        assertEquals(10, tasks.size)
        // 控制变量：所有任务指向同一个槽位，只有强度不同
        assertTrue(tasks.all { it.slot.nodeId == "4" })
        assertEquals(0.1, tasks.first().strength, 1e-9)
        assertEquals(1.0, tasks.last().strength, 1e-9)
    }

    @Test
    fun keepsOtherSlotsUntouchedByOnlyTargetingOneSlot() {
        val slots = listOf(slot(nodeId = "4", name = "a.safetensors"), slot(nodeId = "7", name = "b.safetensors"))
        val tasks = LoraStrengthMatrix.buildTasks(slots, "7", listOf(0.5, 0.7), LoraStrengthTarget.MODEL)
        assertEquals(2, tasks.size)
        assertTrue("只应改 7 号槽位，其余保持原值", tasks.all { it.slot.nodeId == "7" })
    }

    @Test
    fun rejectsUnknownSlot() {
        assertTrue(LoraStrengthMatrix.buildTasks(listOf(slot()), "404", listOf(0.5), LoraStrengthTarget.MODEL).isEmpty())
    }

    @Test
    fun rejectsClipTargetWhenSlotHasNoClipInput() {
        val noClip = slot(clip = null)
        assertTrue(
            LoraStrengthMatrix.buildTasks(listOf(noClip), "4", listOf(0.5), LoraStrengthTarget.CLIP).isEmpty(),
        )
        // 同一个槽位用 MODEL 仍然可用
        assertEquals(1, LoraStrengthMatrix.buildTasks(listOf(noClip), "4", listOf(0.5), LoraStrengthTarget.MODEL).size)
    }

    @Test
    fun truncatesToMaxTasks() {
        val many = (1..100).map { it / 100.0 }
        val tasks = LoraStrengthMatrix.buildTasks(listOf(slot()), "4", many, LoraStrengthTarget.MODEL)
        assertEquals(LoraStrengthMatrix.MAX_TASKS, tasks.size)
    }

    @Test
    fun detectsOverLimit() {
        assertFalse(LoraStrengthMatrix.exceedsLimit(LoraStrengthMatrix.MAX_TASKS))
        assertTrue(LoraStrengthMatrix.exceedsLimit(LoraStrengthMatrix.MAX_TASKS + 1))
    }

    // ===== 展示 =====

    @Test
    fun formatsStrengthWithoutTrailingZeros() {
        assertEquals("1", LoraStrengthMatrix.formatStrength(1.0))
        assertEquals("0.5", LoraStrengthMatrix.formatStrength(0.5))
        assertEquals("0.3", LoraStrengthMatrix.formatStrength(0.30000000000000004))
    }

    @Test
    fun describesStepsWithCount() {
        val text = LoraStrengthMatrix.describeSteps(LoraStrengthMatrix.expandSteps(0.1, 1.0, 0.1))
        assertTrue(text, text.contains("共 10 档"))
        assertEquals("无有效档位", LoraStrengthMatrix.describeSteps(emptyList()))
    }

    @Test
    fun taskLabelCarriesStrengthScopeAndShortName() {
        val task = LoraStrengthMatrix.buildTasks(listOf(slot()), "4", listOf(0.7), LoraStrengthTarget.MODEL).first()
        // label 内部已去目录与扩展名，形如 `lora_a · model 0.70`
        assertEquals("lora_a · model 0.70", task.label)
    }

    // ===== LoraStrengthRun 计数 =====

    @Test
    fun runCountsIncludeCurrentTask() {
        val run = LoraStrengthRun(
            id = "s1",
            workflowPath = "w.json",
            workflowName = "w",
            slotNodeId = "4",
            slotTitle = "加载LoRA",
            target = LoraStrengthTarget.MODEL,
            seed = "1",
            originalModel = 0.8,
            originalClip = 0.8,
            pending = listOf(
                LoraMatrixTask(slot("4"), 0.2, LoraStrengthTarget.MODEL),
                LoraMatrixTask(slot("4"), 0.3, LoraStrengthTarget.MODEL),
            ),
            current = LoraMatrixTask(slot("4"), 0.1, LoraStrengthTarget.MODEL),
            items = listOf(
                StrengthItemResult(0.1, LoraStrengthTarget.MODEL, "4", "a", "a · 0.10", success = true),
            ),
        )
        assertEquals(4, run.total)
        assertEquals(1, run.finished)
        assertEquals(1, run.successCount)
        assertEquals(0, run.failedCount)
    }
}
