package com.local.comfyuimobile.bridge

import com.local.comfyuimobile.model.ParameterKind
import org.json.JSONObject

/**
 * 界面上的参数文本 → 要写回画布的 JSON（纯 Kotlin，可单元测试）。
 *
 * v0.1.87：这段逻辑原来住在 MainViewModel 里，凡解析不出来的一律折叠成 `"0"` /
 * `"0.0"` / `"false"`。后果很具体：用户把 steps / cfg 清空再点生成，节点收到的是
 * 0——而 `FieldValidator` 的注释白纸黑字写着"空值表示沿用默认值，跳过校验"，
 * 生成按钮照常点亮，用户拿不到任何告警。两边的约定正好相反。
 *
 * 现在的规则是：**解析不出来就回退到工作流原值**（[fallbackJson]）。
 * 这样 `ComfyBridge.buildPrompt` 的差集判定（`valueJson != originalValueJson`）
 * 会认为"这个字段没改过"，节点保持原样，与校验器承诺的行为一致。
 *
 * 只有连原值都拿不到时才退回 "0" 这类默认值——那是字段本身就没有原始值的情况，
 * 写代码路径时留着它比抛异常安全。
 */
object FieldValue {

    fun toJson(kind: ParameterKind, value: String, fallbackJson: String = ""): String = when (kind) {
        ParameterKind.INTEGER -> value.toLongOrNull()?.toString() ?: pick(fallbackJson, "0")
        ParameterKind.DECIMAL -> value.toDoubleOrNull()?.toString() ?: pick(fallbackJson, "0.0")
        ParameterKind.BOOLEAN -> value.toBooleanStrictOrNull()?.toString() ?: pick(fallbackJson, "false")
        // COMBO：下拉框被清空、或节点值本来就是 null（ComfyBridge 会把 null 显示成
        // 空串）时同样沿用原值，否则会把空字符串塞进一个只接受枚举的控件里。
        ParameterKind.COMBO ->
            if (value.isBlank()) pick(fallbackJson, JSONObject.quote(value)) else JSONObject.quote(value)
        else -> JSONObject.quote(value)
    }

    private fun pick(fallbackJson: String, default: String): String {
        val trimmed = fallbackJson.trim()
        // JSON null 不是可用的原值，别把它当成"沿用"。
        return trimmed.takeIf { it.isNotBlank() && it != "null" } ?: default
    }
}
