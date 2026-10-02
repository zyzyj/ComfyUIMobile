package com.local.comfyuimobile.model

/**
 * 多账号相关的顺序判定（v0.2.69）。
 *
 * 抽出来是因为一个真机 bug：「每日自动签到」只对**当前选中**的账号执行，
 * 用户加的第二个账号永远签不到——而断签会让连续签到天数从 1 重头算，
 * 正是这个功能最该避免的事。修成遍历所有账号后，执行顺序需要锁住：
 * 当前账号必须排在最前，否则面板上的"今日已签"不会及时更新。
 */
object AiStudioAccountOrder {

    /**
     * 把当前账号排到最前，其余账号**保持原顺序**。
     *
     * 为什么其余要保持原顺序：账号列表是用户添加的顺序，打乱会让日志与面板
     * 对不上（排查时按日志顺序找账号会很痛苦）。
     */
    fun activeFirst(
        accounts: List<AiStudioAccount>,
        activeAccountId: String?,
    ): List<AiStudioAccount> {
        if (accounts.size <= 1) return accounts
        if (activeAccountId.isNullOrBlank()) return accounts
        val active = accounts.firstOrNull { it.id == activeAccountId } ?: return accounts
        return listOf(active) + accounts.filterNot { it.id == active.id }
    }
}
