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
     * 先按 `scheduleName`**精确**匹配（那才是接口要传的值）；没命中再按显示名
     * （label，如 "V100 16GB"）匹配——因为 AI 很容易把界面上看到的名字传进来，
     * 直接报错会让它多绕一圈。
     *
     * **但绝不在多个候选里自己挑**：`V100 16GB` / `V100 32GB` 价格与显存都不同，
     * 猜错等于替用户花钱。这种情况要报错让 AI 澄清（规划书 §3.3 明确要求）。
     * 同样不用 `contains` 之类的模糊匹配，那会引入新的静默降级。
     *
     * @param requested 用户给的值；空白表示"没指定，用第一项"。
     * @return 匹配到的档位；没有可选档位时返回 null。
     * @throws IllegalArgumentException 给了值但匹配不上、或匹配到多个。
     */
    fun choose(schedules: List<AiStudioSchedule>, requested: String?): AiStudioSchedule? {
        if (schedules.isEmpty()) return null
        val wanted = requested?.trim().orEmpty()
        if (wanted.isEmpty()) return schedules.first()

        schedules.firstOrNull { it.scheduleName.equals(wanted, ignoreCase = true) }
            ?.let { return it }

        val byLabel = schedules.filter { it.label.isNotBlank() && it.label.equals(wanted, ignoreCase = true) }
        when (byLabel.size) {
            1 -> return byLabel.first()
            0 -> throw IllegalArgumentException(notFoundMessage(wanted, schedules))
            else -> throw IllegalArgumentException(
                "档位「$wanted」对应多个候选：" +
                    byLabel.joinToString("、") { "${it.scheduleName}（${it.label}）" } +
                    "。请用 scheduleName 指定具体是哪一档（价格与显存可能不同）。",
            )
        }
    }

    private fun notFoundMessage(wanted: String, schedules: List<AiStudioSchedule>): String =
        "档位「$wanted」不在可选列表里。可用：" +
            schedules.joinToString("、") { "${it.scheduleName}（${it.label.ifBlank { it.displayName() }}）" } +
            "。请先用 list_gpu_options 确认。"

    /**
     * 项目当前处于哪种状态（v0.2.97）。纯函数，可单测。
     *
     * 清单 §3.1 要求 `gpu_status` 必须区分这四种——因为它们对应的**下一步完全不同**：
     * `stopped` 要启动，`submitting` 要等，`running` 可以直接干活，
     * `cookie_expired` 要去重新登录。只说"查不到"，AI 只能瞎试。
     */
    enum class ProjectState { STOPPED, SUBMITTING, RUNNING, COOKIE_EXPIRED }

    /**
     * @param loginExpired 平台明确回报了 403 / 需要重新登录
     * @param running 项目是否在运行
     * @param environmentReady 环境地址是否已确认（`running=true` 只是**受理回执**，
     *   真分配还要几秒到几分钟）
     * @param starting 是否已提交启动但环境还没就绪
     */
    fun projectState(
        loginExpired: Boolean,
        running: Boolean,
        environmentReady: Boolean,
        starting: Boolean,
    ): ProjectState = when {
        loginExpired -> ProjectState.COOKIE_EXPIRED
        running && environmentReady -> ProjectState.RUNNING
        starting -> ProjectState.SUBMITTING
        running -> ProjectState.SUBMITTING
        else -> ProjectState.STOPPED
    }

    /** 状态的人话描述（带该做什么）。 */
    fun describeState(state: ProjectState): String = when (state) {
        ProjectState.STOPPED -> "stopped（未运行）——可用 start_gpu 启动"
        ProjectState.SUBMITTING -> "submitting（已提交，平台正在分配环境）——等 1-2 分钟后再查"
        ProjectState.RUNNING -> "running（运行中，环境就绪）"
        ProjectState.COOKIE_EXPIRED -> "cookie_expired（登录态已失效）——请在 App 的「账号」页重新登录"
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
