package com.local.comfyuimobile.model

/**
 * 生图进度的统一文案（v0.2.90）。
 *
 * 抽成纯函数的理由与 `McpServerManager.resolvePort` 一样——**界面卡片与后台通知
 * 必须显示同一句话**。以前卡片写"正在跟踪中"、通知写"正在生成 47%"，两处各自
 * 拼字符串，改一处就对不上。
 *
 * 为什么"节点名 + 已用时间"优先于百分比：**反代环境下精确百分比物理上不可靠**——
 * 反代每约 2.3 秒掐断一次 WebSocket，而 ComfyUI 重连**不补发百分比**（只补发当前
 * 节点名）。断线期间的 progress 永久丢失，于是 47% 这种数字经常是假的、甚至长时间
 * 不动。
 *
 * 而节点名能经重连补发救回来（ComfyUI 的 `executing` 带 `last_node_id`），
 * 已用时间是本机计算、不依赖网络。**"它在动吗、跑到哪一步了"比一个假的 47% 有用。**
 * 百分比降级为辅助信息，只在确实拿到时追加。
 */
object JobProgressText {

    /**
     * @param node 当前节点名（可能为空）
     * @param elapsedMillis 已用时长；null 表示不知道
     * @param percent 0-100 的百分比；null 或负数表示没有可信值
     * @param pending 是否在排队（排队时说"排队中"而不是"执行中"）
     */
    fun title(
        node: String?,
        elapsedMillis: Long?,
        percent: Int? = null,
        pending: Boolean = false,
    ): String = buildString {
        append(if (pending) "排队中" else "正在生成")
        percent?.takeIf { it in 0..100 }?.let { append(" $it%") }
        elapsedMillis?.takeIf { it >= 0 }?.let { append(" · 已用 ${formatDuration(it)}") }
    }

    /** 副标题：节点名或回落的说明。 */
    fun subtitle(node: String?, workflowName: String? = null): String {
        val cleaned = node?.trim().orEmpty()
        return when {
            cleaned.isNotBlank() -> cleaned
            workflowName?.isNotBlank() == true -> workflowName
            else -> "等待服务器推进…"
        }
    }

    /** 卡片用的一行式（节点名 + 已用时间）。 */
    fun compact(node: String?, elapsedMillis: Long?, percent: Int? = null): String {
        val head = subtitle(node).let { if (it == "等待服务器推进…" && elapsedMillis == null) "正在生成" else it }
        return buildString {
            append(head)
            percent?.takeIf { it in 0..100 }?.let { append("（$it%）") }
            elapsedMillis?.takeIf { it >= 0 }?.let { append(" · 已用 ${formatDuration(it)}") }
        }
    }

    /** 秒 → `1:23` / `1:02:03`。 */
    fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
