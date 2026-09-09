package com.local.comfyuimobile.bridge

import com.local.comfyuimobile.model.ParameterField
import com.local.comfyuimobile.model.ParameterKind
import com.local.comfyuimobile.model.FieldProblem

object FieldValidator {
    fun detailedProblems(fields: List<ParameterField>): List<FieldProblem> = buildList {
        fields.filterNot { it.linked }.forEach { field ->
            val message = when (field.kind) {
                // 空值表示"沿用默认值"，跳过校验；只有填了非空但非法时才报错。
                ParameterKind.INTEGER -> if (field.displayValue.isNotBlank() && field.displayValue.toLongOrNull() == null) "${field.label} 不是有效整数" else null
                ParameterKind.DECIMAL -> if (field.displayValue.isNotBlank() && field.displayValue.toDoubleOrNull()?.isFinite() != true) "${field.label} 不是有效数字" else null
                // v0.1.87：COMBO 以前是唯一不跳空值的分支。节点值本来就是 null 时
                // ComfyBridge 会把 displayValue 显示成空串，于是"XX 的选项已经失效"
                // 常驻，而生成按钮只认 localProblems.isEmpty()，整条生成路径被一个
                // 用户根本没法在下拉框里修好的误判锁死。这里与 INTEGER/DECIMAL 对齐：
                // 空值同样是"沿用默认值"，不报错（FieldValue 会把原值写回去）。
                ParameterKind.COMBO -> if (field.displayValue.isNotBlank() && field.options.isNotEmpty() && field.displayValue !in field.options) "${field.label} 的选项已经失效" else null
                ParameterKind.IMAGE, ParameterKind.VIDEO -> if (field.displayValue.isBlank()) "${field.label} 尚未选择文件" else null
                else -> null
            }
            if (message != null) add(FieldProblem(field.key, field.nodeId, message))
        }
    }

    fun problems(fields: List<ParameterField>): List<String> = detailedProblems(fields).map { it.message }
}
