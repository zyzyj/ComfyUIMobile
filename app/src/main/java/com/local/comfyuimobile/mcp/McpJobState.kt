package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.model.JobState
import com.local.comfyuimobile.model.JobSummary
import org.json.JSONObject

/**
 * MCP 任务状态判定（v0.2.90）。
 *
 * 抽成纯函数是为了**堵死平行路径**：以前「关机判定」读 `/queue`、
 * 「完成判定」读 `/history`，两处信号源不一致——后果是任务还在排队时
 * `history` 里没有它，工具只能一直轮询到超时，并把"排队中"误报成
 * "运行中"。现在只有一个函数回答"这任务现在什么状态"。
 *
 * 判定顺序（先队列后历史）：
 *  1. 队列里且在 RUNNING → running
 *  2. 队列里且在 PENDING → queued（排队中，尚未开始）
 *  3. 不在队列 → 看 history：有输出 → done；有记录无输出 → failed；都没有 → unknown
 *
 * 队列读不到时**不**直接判失败：那是网络问题，不是任务状态。
 */
internal object McpJobState {

    enum class Phase(val label: String) {
        QUEUED("queued"),
        RUNNING("running"),
        DONE("done"),
        FAILED("failed"),
        UNKNOWN("unknown"),
        ;

        companion object {
            /** 模型读到的就是 label 字符串，两端共用同一份映射。 */
            fun parse(value: String?): Phase = entries.firstOrNull { it.label == value } ?: UNKNOWN
        }
    }

    data class Verdict(
        val phase: Phase,
        /** 队内位置（1 起）；仅 queued/running 有意义。 */
        val position: Int? = null,
        val message: String = "",
    )

    /**
     * @param queue 当前队列快照（`/queue` 解析结果）；传 null 表示读不到队列
     * @param history `/history/{id}` 的返回；null 表示读不到
     * @param hasOutputs 该任务在 history 里是否产出了图片
     */
    fun resolve(
        promptId: String,
        queue: List<JobSummary>?,
        history: JSONObject?,
        hasOutputs: Boolean,
    ): Verdict {
        if (promptId.isBlank()) return Verdict(Phase.UNKNOWN, message = "缺少 job_id")
        // 队列优先：排队中的任务在 history 里完全不存在，只看 history 会把它判成
        // "已消失/失败"，那是错的——必须先问队列。
        if (queue != null) {
            val index = queue.indexOfFirst { it.id == promptId }
            if (index >= 0) {
                val job = queue[index]
                return when (job.state) {
                    JobState.RUNNING -> Verdict(Phase.RUNNING, position = index + 1, message = "执行中")
                    JobState.PENDING -> Verdict(Phase.QUEUED, position = index + 1, message = "排队中，前面还有 ${index} 个")
                    else -> Verdict(Phase.RUNNING, position = index + 1, message = job.state.name)
                }
            }
        }
        val entry = history?.optJSONObject(promptId)
        return when {
            entry != null && hasOutputs -> Verdict(Phase.DONE, message = "已完成")
            entry != null -> Verdict(Phase.FAILED, message = "任务已结束但没有产出图片")
            else -> Verdict(Phase.UNKNOWN, message = "队列与历史里都查不到该任务（可能已被清理或 job_id 有误）")
        }
    }
}
