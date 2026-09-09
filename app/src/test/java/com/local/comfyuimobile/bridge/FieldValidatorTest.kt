package com.local.comfyuimobile.bridge

import com.local.comfyuimobile.model.ParameterField
import com.local.comfyuimobile.model.ParameterKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldValidatorTest {
    @Test fun rejectsInvalidNativeValuesButKeepsUnsupportedControls() {
        val fields = listOf(
            field("steps", ParameterKind.INTEGER, "x"),
            field("sampler", ParameterKind.COMBO, "missing", listOf("euler")),
            field("image", ParameterKind.IMAGE, ""),
            field("mask", ParameterKind.UNSUPPORTED, ""),
        )
        val problems = FieldValidator.problems(fields)
        assertEquals(3, problems.size)
        assertTrue(problems.any { it.contains("steps") })
    }

    @Test fun ignoresValuesControlledByLinks() {
        assertTrue(FieldValidator.problems(listOf(field("steps", ParameterKind.INTEGER, "x", linked = true))).isEmpty())
    }

    @Test fun skipsEmptyNumericFieldsInsteadOfFailing() {
        // 空值表示沿用默认值，不应报"不是有效数字"。
        assertTrue(FieldValidator.problems(listOf(field("steps", ParameterKind.INTEGER, ""))).isEmpty())
        assertTrue(FieldValidator.problems(listOf(field("cfg", ParameterKind.DECIMAL, ""))).isEmpty())
        // 非空但非法的仍然报错。
        assertEquals(1, FieldValidator.problems(listOf(field("steps", ParameterKind.INTEGER, "x"))).size)
        assertEquals(1, FieldValidator.problems(listOf(field("cfg", ParameterKind.DECIMAL, "abc"))).size)
    }

    private fun field(
        name: String,
        kind: ParameterKind,
        value: String,
        options: List<String> = emptyList(),
        linked: Boolean = false,
    ) = ParameterField(
        key = "1/$name", nodeId = "1", nodeTitle = "Node", nodeType = "Node", name = name,
        label = name, widgetType = "widget", kind = kind, valueJson = "null", displayValue = value,
        options = options, linked = linked,
    )

    @Test fun skipsEmptyComboInsteadOfLockingGeneration() {
        // v0.1.87：COMBO 以前是唯一不跳空值的分支。节点值本来就是 null 时
        // ComfyBridge 会把 displayValue 显示成空串，"XX 的选项已经失效"常驻，
        // 而生成按钮只认 localProblems.isEmpty()，整条生成路径被锁死且用户没法自救。
        assertTrue(
            FieldValidator.problems(listOf(field("sampler", ParameterKind.COMBO, "", listOf("euler")))).isEmpty(),
        )
        // 非空但不在选项里，仍然要报（这是真的失效了，得提示用户）。
        assertEquals(
            1,
            FieldValidator.problems(listOf(field("sampler", ParameterKind.COMBO, "gone", listOf("euler")))).size,
        )
        // 没有 options 的 combo（拿不到枚举）不报错。
        assertTrue(
            FieldValidator.problems(listOf(field("sampler", ParameterKind.COMBO, ""))).isEmpty(),
        )
    }
}
