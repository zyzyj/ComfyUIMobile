package com.local.comfyuimobile.bridge

import com.local.comfyuimobile.model.ParameterKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v0.1.87：空值 / 非法值不再被静默折叠成 0、0.0、false。
 *
 * 背景见 FieldValue 的说明——以前把 steps 清空再点生成，节点收到的是 0 步，
 * 而 FieldValidator 明明写着"空值表示沿用默认值"。
 */
class FieldValueTest {

    @Test fun keepsValidNumbers() {
        assertEquals("20", FieldValue.toJson(ParameterKind.INTEGER, "20"))
        assertEquals("7.5", FieldValue.toJson(ParameterKind.DECIMAL, "7.5"))
        assertEquals("true", FieldValue.toJson(ParameterKind.BOOLEAN, "true"))
        assertEquals("false", FieldValue.toJson(ParameterKind.BOOLEAN, "false"))
    }

    @Test fun blankValueFallsBackToWorkflowOriginalInsteadOfZero() {
        // 这是 H1 的核心：清空输入框 = 沿用默认值，绝不是 0。
        assertEquals("20", FieldValue.toJson(ParameterKind.INTEGER, "", "20"))
        assertEquals("7.0", FieldValue.toJson(ParameterKind.DECIMAL, "", "7.0"))
        assertEquals("true", FieldValue.toJson(ParameterKind.BOOLEAN, "", "true"))
    }

    @Test fun unparsableValueAlsoFallsBackToOriginal() {
        assertEquals("20", FieldValue.toJson(ParameterKind.INTEGER, "abc", "20"))
        assertEquals("1.5", FieldValue.toJson(ParameterKind.DECIMAL, "abc", "1.5"))
    }

    @Test fun withoutOriginalStillUsesSafeDefault() {
        // 字段本身没有原值时的兜底，保持和旧版一致，不能变成空串或非法 JSON。
        assertEquals("0", FieldValue.toJson(ParameterKind.INTEGER, ""))
        assertEquals("0.0", FieldValue.toJson(ParameterKind.DECIMAL, ""))
        assertEquals("false", FieldValue.toJson(ParameterKind.BOOLEAN, ""))
    }

    @Test fun jsonNullIsNotUsableAsOriginal() {
        // ComfyBridge 对 null 值产出的原值就是 "null"，不能把它当"沿用"。
        assertEquals("0", FieldValue.toJson(ParameterKind.INTEGER, "", "null"))
    }

    @Test fun blankComboFallsBackToOriginalOption() {
        assertEquals(
            "\"euler\"",
            FieldValue.toJson(ParameterKind.COMBO, "", "\"euler\""),
        )
        assertEquals(
            "\"euler\"",
            FieldValue.toJson(ParameterKind.COMBO, "euler"),
        )
    }

    @Test fun textValuesAreQuoted() {
        assertEquals("\"masterpiece\"", FieldValue.toJson(ParameterKind.MULTILINE, "masterpiece"))
        assertEquals("\"\"", FieldValue.toJson(ParameterKind.MULTILINE, ""))
    }

    @Test fun fallingBackKeepsChangedFieldsEmpty() {
        // buildPrompt 用 `valueJson != originalValueJson` 判定"用户改过"。
        // 回退到原值 ⇒ 差集为空 ⇒ 这个字段根本不会被写回画布。
        val original = "20"
        val cleared = FieldValue.toJson(ParameterKind.INTEGER, "", original)
        assertEquals("清空后应与原值相等，才会被判定为未修改", original, cleared)
    }
}
