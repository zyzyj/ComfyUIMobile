package com.local.comfyuimobile.mcp

/**
 * GPU 会话时长（v0.3.7，P0-3）。纯函数，可单测。
 *
 * ## 为什么必须"真记时刻"而不是推算
 *
 * 上一轮我（AI）被要求补"已运行 1.3 小时"，我拒绝了并说明理由：`updatedAt` 是
 * **最后更新时间**，不是运行起始时间，拿它算必然出错——**编一个账单比不给更糟**。
 * 那个拒绝是对的，要保留。所以正确做法是：`startGpu` **成功返回时**记下真实时刻，
 * 落盘（进程被杀也不丢），之后拿它算。
 *
 * ## 为什么在估不出来时返回 null
 *
 * 没有开始时刻（老数据、或从没通过 MCP 启动过）就是不知道。返回 null 而不是
 * "0 分钟"或现有档位的猜测——0 会被读成"没在计费"，而它可能已经烧了一夜。
 */
internal object McpSessionClock {

    /**
     * 会话运行时长（分钟）。
     *
     * @param startedAt 记录的启动时刻（毫秒）；null / <=0 表示未知
     * @param sameTarget 记录指向的目标（项目）是否与当前查询的一致
     * @param now 当前时刻（便于测试）
     * @return 分钟数；**未知时返回 null**，调用方必须如实说"不知道"
     */
    fun runningMinutes(startedAt: Long?, sameTarget: Boolean, now: Long): Long? {
        if (startedAt == null || startedAt <= 0L) return null
        if (!sameTarget) return null
        // 时钟回拨/异常值保护：未来时刻不可能是"已运行"。
        val elapsed = now - startedAt
        if (elapsed < 0) return null
        // 向下取整到分钟：宁可少报一分钟，也不要让用户以为多烧了（也避免"已运行 0.02 分钟"这种噪声）。
        return elapsed / 60_000L
    }

    /**
     * 是否该在 `gpu_status` 里给出「已运行」这一行。
     *
     * 只对**真的在运行**且有可信时刻的情况给。项目没在跑时给时长没有意义
     * （用户会以为还在扣）。
     */
    fun shouldReport(running: Boolean, minutes: Long?): Boolean = running && minutes != null

    /**
     * 估算已消耗的算力卡（点数）。
     *
     * @param costPerHour 该档位每小时消耗；null / <=0 表示平台没给
     * @return 估算值；**任一输入不可信就返回 null**
     */
    fun estimateCost(minutes: Long?, costPerHour: Double?): Double? {
        if (minutes == null || costPerHour == null || costPerHour <= 0.0) return null
        return minutes / 60.0 * costPerHour
    }
}
