package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.data.SubmittedJobRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `list_my_jobs` 的输出组装（v0.3.7）。纯函数，可单测。
 *
 * ## 为什么单独抽出来
 *
 * 这个工具存在的**唯一意义**是让 AI 在丢了 job_id 时不要重新提交。所以输出的每
 * 一句话都要朝那个方向写——而"引导文案"最容易在后续重构中被顺手改坏（本项目已
 * 有先例：引导里的工具名写错、指向不存在的工具）。抽成纯函数就能用单测把它钉住。
 */
internal object McpJobListFormatter {

    /** 单条记录 + 它对端需要的实时状态。 */
    data class Entry(
        val record: SubmittedJobRecord,
        /** 实时状态标签（如 running / done）；空 = 查不到。 */
        val stateLabel: String,
        /** 该状态是否是终态且已产出图片。 */
        val hasImages: Boolean,
    )

    private val TIME_FORMAT = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm", Locale.US) }

    /**
     * 组装输出。
     *
     * @param entries 已按时间倒序（最新的在前）排好的记录
     * @param totalShown 本次实际展示的条数
     */
    fun render(entries: List<Entry>, totalShown: Int): String {
        if (entries.isEmpty()) {
            return "本会话没有已提交的任务记录。\n" +
                "如果你确信提交过，可能是记录已被清理（只保留最近 $MAX_RECORDS 条）；" +
                "此时才需要重新提交。"
        }
        val pending = entries.count { it.hasImages && !it.record.fetched }
        return buildString {
            appendLine("近期提交过的任务（共 ${entries.size} 条，展示 $totalShown 条，最新的在前）：")
            entries.forEach { entry ->
                appendLine("  " + line(entry))
            }
            if (pending > 0) {
                appendLine()
                appendLine("其中 $pending 条已完成但**还没取图**——用 job_status(job_ids=[...]) 取回它们。")
            }
            appendLine()
            // 这是本工具存在的意义，必须写死在这里。
            append(
                "提示：这些任务**已经提交过**，不要用 generate 重新提交——那会再出一张图、" +
                    "再烧一次算力卡。只有确认记录已被清理时才需要重新提交。",
            )
        }
    }

    private fun line(entry: Entry): String = buildString {
        val record = entry.record
        append(record.jobId.take(12))
        append("  ")
        append(if (record.submittedAt > 0) TIME_FORMAT.get()!!.format(Date(record.submittedAt)) else "时间未知")
        append("  ")
        append(entry.stateLabel.ifBlank { "状态未知" })
        if (entry.hasImages && !record.fetched) append("  ← 未取图")
    }

    /** 最多展示多少条（工具一次返回太多会白烧对端上下文）。 */
    const val MAX_SHOWN = 50

    /** 与 [com.local.comfyuimobile.data.MAX_SUBMITTED_JOBS] 对应的保留上限，用于提示文案。 */
    const val MAX_RECORDS = 1000
}
