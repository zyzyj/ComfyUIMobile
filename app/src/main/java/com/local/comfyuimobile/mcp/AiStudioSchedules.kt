package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.model.AiStudioSchedule

/**
 * GPU 档位的展示与匹配（v0.2.97）。纯函数，可单测。
 *
 * 为什么把匹配单独拎出来：`startProject` 会把档位名**原样透传**给平台，传错时平台
 * 不报错、而是按默认档启动——用户以为选了 A100，实际开的是 V100，账单照扣。
 * 这类"静默降级"没法靠看日志发现，只能靠提交前的匹配校验挡住。
 */
internal object AiStudioSchedules {

    /**
     * 把用户/模型给的档位名对到真实档位。
     *
     * 故意**只接受精确匹配**（大小写不敏感、去空白）：模糊匹配会引入新的静默降级
     * ——比如用 `contains` 的话，传 "V100" 可能命中 "V100 16GB" 也可能命中
     * "V100 32GB"，两者价格与显存都不同，猜错等于替用户花钱。
     *
     * @param requested 用户给的值；空白表示"没指定，用第一项"。
     * @return 匹配到的档位；没有可选档位时返回 null。
     * @throws IllegalArgumentException 给了值但匹配不上（消息里带上可选项）。
     */
    fun choose(schedules: List<AiStudioSchedule>, requested: String?): AiStudioSchedule? {
        if (schedules.isEmpty()) return null
        val wanted = requested?.trim().orEmpty()
        if (wanted.isEmpty()) return schedules.first()
        return schedules.firstOrNull { it.scheduleName.equals(wanted, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "档位「$wanted」不在可选列表里（必须精确匹配）。可用：" +
                    schedules.joinToString("、") { it.scheduleName } +
                    "。请先用 list_gpu_options 确认。",
            )
    }

    /** 单档的展示行。scheduleName 是接口真正要传的值，必须原样带出。 */
    fun describe(schedule: AiStudioSchedule): String {
        // 消耗与配额都可能是 0 / 未知，0 时不展示（与界面同一处理：不显示"0 算力卡/小时"）。
        val cost = schedule.costPerHour
            ?.takeIf { it > 0 }
            ?.let { " · ${trimNumber(it / 100.0)} 算力卡/小时" }
            .orEmpty()
        val quota = schedule.weekRemainingMinutes
            ?.takeIf { it > 0 }
            ?.let { " · 本周剩余 ${trimNumber(it)} 分钟" }
            .orEmpty()
        val usable = if (schedule.available) "" else " · 当前不可用"
        return "${schedule.displayName()} · schedule=${schedule.scheduleName}$cost$quota$usable"
    }

    /**
     * 去掉多余的小数（`3.0` → `3`，`3.8` → `3.8`）。
     *
     * 与界面上的 trimNumber 同一条规则：同一份数据在工具返回和界面展示里应该长得一样，
     * 否则模型看到"3.80"、用户看到"3.8"，对不上时会怀疑数据是不是同一个。
     */
    private fun trimNumber(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }
}
