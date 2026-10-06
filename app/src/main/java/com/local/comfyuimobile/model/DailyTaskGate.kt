package com.local.comfyuimobile.model

/**
 * 每日自动任务的执行门闩（v0.2.92）。
 *
 * 抽成纯函数的原因与 `McpServerManager.resolvePort` 一样——**判断只写一份**。
 * 这里的 bug 是真实踩过的：
 *
 * `aiStudioAutoDailyTasks` 原本只用"任务在跑吗"（`aiStudioActionJob?.isActive`）
 * 挡重复调用，但 `runDailyTasksFor` 末尾会调 `aiStudioRefreshAccount()`——那是
 * **另一个 job**，不受前者的忙标志约束。而它一旦刷新令牌就会 `persistAiStudio`
 * 写偏好，DataStore 偏好一变就再推一次 collect，又调回 `aiStudioAutoDailyTasks`。
 *
 * 真机日志（用户反馈「无法选中 GPU 档位、只能默认档位启动」那份）里，
 * 一秒内冒出好几轮积分 / 算力卡 / A币请求，间隔约 100ms——平台风控随即将账号
 * 标记为「存在安全风险」（错误码 8407），而**读档位与启动环境走同一套风控**，
 * 于是一起失败：档位列表拉不到，界面只剩默认档位可选。
 *
 * 记忆点：**"任务在跑吗"不能替代"今天跑过了吗"**。前者防的是并发，后者防的是
 * 事件循环引发的重复；两者要同时存在。
 */
object DailyTaskGate {

    /** 日期串（年-年积日），用作"今天"的比较键。不用 LocalDate 是为了不引 java.time 依赖差异。 */
    fun dayKey(timestampMillis: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestampMillis
        return "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
    }

    /**
     * 筛出这次真正需要跑任务的账号。
     *
     * @param alreadyRun accountId → 上次跑过的日期串（调用方持有的门闩表）
     * @return 需要跑的账号 id；同时把这些 id 的日期串写回 [alreadyRun]（调用方原地更新）
     */
    fun claim(accountIds: List<String>, alreadyRun: MutableMap<String, String>, nowMillis: Long): List<String> {
        val today = dayKey(nowMillis)
        val pending = accountIds.filter { alreadyRun[it] != today }
        pending.forEach { alreadyRun[it] = today }
        return pending
    }

    /** 该账号今天是否已经跑过（界面/日志用）。 */
    fun hasRunToday(accountId: String, alreadyRun: Map<String, String>, nowMillis: Long): Boolean =
        alreadyRun[accountId] == dayKey(nowMillis)
}
