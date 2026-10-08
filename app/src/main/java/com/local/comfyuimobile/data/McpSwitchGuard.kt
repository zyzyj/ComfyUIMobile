package com.local.comfyuimobile.data

/**
 * MCP 开关的写入序号守卫（v0.3.5，P0-3）。纯函数，可单测。
 *
 * 为什么需要：`MainViewModel`（用户点开关）与 `McpServerService.stopServer()`
 * 都在**异步**写同一个偏好，没有任何顺序保证。上一次停止的落盘若迟到执行，
 * 会把用户这次的 true 盖成 false——表现就是「打开就关上」。
 *
 * 这与 v0.2.46 给账号字段做的「内存领先磁盘」是**同一个病**：
 * 账号字段修了（`pendingAccountId`），MCP 开关没修。
 *
 * 两者都要做：内存保护挡同一进程内的 UI 抖动，序号挡跨实例/迟到的异步写入。
 */
internal object McpSwitchGuard {

    /**
     * 这次写入是否该丢弃。
     *
     * @param seq 本次写入的序号（调用方用单调递增的时间戳）
     * @param recorded 已记录的序号
     * @return true = 过期写入，必须丢弃（不能覆盖更新的值）
     */
    fun shouldDiscard(seq: Long, recorded: Long): Boolean = seq < recorded

    /** 新值是否胜出（与 [shouldDiscard] 互补，写下来让调用点读起来更清楚）。 */
    fun shouldApply(seq: Long, recorded: Long): Boolean = !shouldDiscard(seq, recorded)
}
