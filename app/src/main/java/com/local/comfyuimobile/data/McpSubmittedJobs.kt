package com.local.comfyuimobile.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条「已提交任务」的记录（v0.3.7）。
 *
 * ## 为什么需要它
 *
 * 在此之前偏好里只存了一批裸 job_id（`submitted_jobs`）。那对界面任务跟踪够用，
 * 但 MCP 侧因此**没有任何办法找回任务**：AI 一旦丢了 job_id（上下文压缩、会话
 * 中断、App 重启），只能凭记忆猜，或者**重新提交 → 多出一张图 → 双倍算力卡**。
 *
 * 补上提交时刻与「是否已取图」后，才能回答「我提交过什么、哪些还没取」——
 * 这正是 `list_my_jobs` 存在的意义。
 */
internal data class SubmittedJobRecord(
    val jobId: String,
    /** 提交时刻（毫秒）。**0 表示未知**（旧版本写入的数据，或界面提交的）。 */
    val submittedAt: Long = 0L,
    /** 是否已把图取回（MCP 侧 job_status 成功拿到图片时置位）。 */
    val fetched: Boolean = false,
)

/**
 * `submitted_jobs` 偏好的编解码（v0.3.7）。纯函数，可单测。
 *
 * ## 为什么不用新开一个存储
 *
 * 界面任务跟踪（`MainViewModel`）与 MCP 侧找回读的是**同一批任务**。分成两份存储
 * 必然出现「界面里有、AI 查不到」这种平行路径——本项目已因此返工十次。
 *
 * ## 向后兼容是必须的
 *
 * 旧版本把这里存成 `["id1","id2"]` 的纯字符串数组。**必须继续认它**：解析不出来
 * 就等于把老用户已提交的任务列表清空，表现是"任务跟踪又失灵了"——正是本工具要修的
 * 那个病。旧的记录解析为 [SubmittedJobRecord.submittedAt] = 0（诚实地说"不知道"），
 * 而不是编一个当前时间。
 */
internal object McpSubmittedJobs {

    fun encode(records: List<SubmittedJobRecord>): String {
        val array = JSONArray()
        records.forEach { record ->
            if (record.jobId.isBlank()) return@forEach
            array.put(
                JSONObject()
                    .put("id", record.jobId)
                    .put("at", record.submittedAt)
                    .put("fetched", record.fetched),
            )
        }
        return array.toString()
    }

    /** 解析；无法识别的条目**跳过而不是整份丢弃**（一条坏数据不该毁掉全部记录）。 */
    fun decode(raw: String): List<SubmittedJobRecord> = runCatching {
        val array = JSONArray(raw.ifBlank { "[]" })
        buildList {
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    // 新格式：对象。
                    is JSONObject -> {
                        val id = item.optString("id")
                        if (id.isBlank()) continue
                        add(
                            SubmittedJobRecord(
                                jobId = id,
                                submittedAt = item.optLong("at").coerceAtLeast(0L),
                                fetched = item.optBoolean("fetched"),
                            ),
                        )
                    }
                    // 旧格式：裸字符串 id。submittedAt=0 表示"未知"。
                    is String -> if (item.isNotBlank()) add(SubmittedJobRecord(jobId = item))
                    else -> Unit
                }
            }
        }
    }.getOrDefault(emptyList())

    /**
     * 整体覆盖时的记录合并：**保留已有记录的时间与取图标记**。
     *
     * 界面在出图提交后会写 `submittedJobIds + newId`（整体覆盖）。若那一步把记录
     * 重建一遍，MCP 侧刚记下的提交时刻就会被抹成 0——`list_my_jobs` 又答不出
     * 「什么时候提交的」。所以覆盖时按 id 继承旧记录的字段。
     */
    fun mergeForOverwrite(
        existing: List<SubmittedJobRecord>,
        ids: Collection<String>,
    ): List<SubmittedJobRecord> {
        val known = existing.associateBy { it.jobId }
        return ids.filter { it.isNotBlank() }.map { id ->
            known[id] ?: SubmittedJobRecord(jobId = id)
        }
    }
}
