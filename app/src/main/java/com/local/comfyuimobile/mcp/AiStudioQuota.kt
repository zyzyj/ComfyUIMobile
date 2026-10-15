package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.model.AiStudioSchedule

/**
 * 档位与「本周配额」的关联（v0.3.8）。纯函数，可单测。
 *
 * ## 为什么需要
 *
 * `AiStudioSchedule.weekQuotaType` 这个字段**一直以来只被赋值、从未被读**——
 * 而它正是把「档位」与「本周剩余额度」接起来的唯一桥梁。
 *
 * 结果是界面直接遍历 `weekQuota` 这个 map 显示「X 小时」，**每档都印同一个数**：
 * 真机实测 CPU / DCU / V100 全显示 20.2 小时，而紧挨着的提示语却写着
 * 「同一份算力卡换成不同显卡，能跑的小时数不一样」——**数字与提示语自相矛盾**。
 * 用户据此以为 V100 还剩 20 小时很宽裕，实际 32G 已经开不了了。
 *
 * 接上 `weekQuotaType` 后，至少能做到「这一档对应哪个配额池、池子还剩多少」，
 * 而不是给每个档位都印一遍同样的总数。
 *
 * ## 为什么不做「折算成该档能跑几小时」
 *
 * 那需要 `costPerHour`（每小时消耗）。而它的**单位没查清**：
 * 代码注释说是「分」（所以展示时除以 100），日志里 V100 16G 是 `"200"`
 * （按注释 = 2.0 点/小时），可官方口径是 0.5 点/小时——差 4 倍。
 *
 * 单位错的话，折算出来的数字**比不折算更误导**：用户会以为自己还能跑 4 倍的时间。
 * 所以这里只做「关联」，把折算留给单位确认之后（`AiStudioQuota.unavailableNote`
 * 那类判断同理，不确定就不说）。
 */
internal object AiStudioQuota {

    /**
     * 一个配额池的展示信息。
     *
     * @param type 配额类型（V100 / A100 / DCU / DEV），取自 `weekQuota` 的 key
     * @param remainingMinutes 该池本周剩余（分钟）
     */
    data class Pool(val type: String, val remainingMinutes: Double)

    /**
     * 某档位对应的配额池剩余；对不上时返回 null（**不要拿别的池顶替**）。
     *
     * 对不上的常见原因：`weekQuotaType` 为空（平台没给），或类型名大小写/命名不同。
     * 这时如实显示「未知」比印一个错的数好。
     */
    fun poolFor(schedule: AiStudioSchedule, pools: Map<String, Double>): Pool? {
        val want = schedule.weekQuotaType.trim()
        if (want.isEmpty()) return null
        // 大小写不敏感：平台同一份数据在不同接口里大小写不稳（本项目已多次遇到）。
        val entry = pools.entries.firstOrNull { it.key.equals(want, ignoreCase = true) } ?: return null
        return Pool(entry.key, entry.value)
    }

    /**
     * 某档位的配额说明行；无法关联时返回 null，由调用方决定怎么显示。
     *
     * **刻意不折算成小时**——见类注释里关于 `costPerHour` 单位的说明。
     */
    fun describePool(schedule: AiStudioSchedule, pools: Map<String, Double>): String? {
        val pool = poolFor(schedule, pools) ?: return null
        return "${AiStudioSchedules.trim(pool.remainingMinutes)} 分钟（${pool.type} 配额池）"
    }

    /**
     * 配额池列表里那些**没有任何档位引用**的类型。
     *
     * 存在的意义：如果全都不引用，说明 `weekQuotaType` 这个字段在当前平台版本下
     * 拿不到了（接口改版），界面就该退回原来的「只罗列池子」而不是显示一片空白。
     * 这样这次改动不会把旧行为弄丢。
     */
    fun unmatchedPools(schedules: List<AiStudioSchedule>, pools: Map<String, Double>): List<Pool> {
        val used = schedules.mapNotNull { poolFor(it, pools)?.type }.toSet()
        return pools.entries
            .filter { it.key !in used }
            .map { Pool(it.key, it.value) }
    }

    /**
     * 某档位的**实际可跑时长**（分钟）（v0.3.8，P0-4）。纯函数，可单测。
     *
     * ## 公式
     *
     * ```
     * 可用时间 = min( 算力卡余额 ÷ (costPerHour / 100), 该档周配额剩余 )
     * ```
     *
     * 两者取小——**两个限制哪个先到就停在哪**：余额管总账，周配额管该档的上限。
     *
     * ## 单位依据（以前存疑，现已确认）
     *
     * v0.3.7 刻意没做折算，因为 `costPerHour` 单位存疑（注释说"分"，日志 `"200"`
     * ÷ 100 = 2 点/小时，而官方口径 0.5 点/小时，差 4 倍）。
     *
     * **官网档位弹窗截图解掉了这个疑点**：
     * ```
     * 算力卡余额 8.0 点  →  选中环境可用时间 4.0 小时
     * 8.0 ÷ 4.0 = 2.0 点/小时  ≡  costPerHour "200" ÷ 100  ✓
     * ```
     * 所以代码的 `/100` 是对的，官方 0.5 那个口径不适用于当前平台费率（或已调整）。
     *
     * @param balanceMinutes 算力卡余额（分钟，即 `computeCardMinutes`）
     * @param costPerHour 该档每小时消耗的原始值（平台给的，除以 100 才是"点"）
     * @param poolMinutes 该档周配额剩余（分钟）；null = 不知道，则不参与取小
     * @return 可跑分钟数；**任一关键输入不可信就返回 null**（不编数字）
     */
    fun runnableMinutes(balanceMinutes: Double?, costPerHour: Double?, poolMinutes: Double?): Double? {
        if (balanceMinutes == null || balanceMinutes <= 0.0) return null
        if (costPerHour == null || costPerHour <= 0.0) return null
        // 余额（分钟）先转成"点"：60 分钟 = 1 点。
        val points = balanceMinutes / 60.0
        val pointsPerHour = costPerHour / 100.0
        if (pointsPerHour <= 0.0) return null
        val byBalance = points / pointsPerHour * 60.0
        // 周配额未知时不参与取小（不能拿 null 当 0，那会把可跑时长算成 0）。
        val pool = poolMinutes?.takeIf { it > 0.0 } ?: return byBalance
        return minOf(byBalance, pool)
    }

    /**
     * 可跑时长的展示文案（v0.3.8，P0-4）；算不出来返回 null。
     *
     * 必须写明**是哪个限制**在卡——用户要知道"是余额不够还是这档配额用完"，
     * 因为下一步完全不同（充值/等下周 vs 换档位）。
     */
    fun runnableLabel(balanceMinutes: Double?, costPerHour: Double?, poolMinutes: Double?): String? {
        val total = runnableMinutes(balanceMinutes, costPerHour, poolMinutes) ?: return null
        val byBalance = runnableMinutes(balanceMinutes, costPerHour, null)
        val limitedByPool = byBalance != null && poolMinutes != null && poolMinutes > 0 && poolMinutes < byBalance
        val reason = if (limitedByPool) "受该档周配额限制" else "受算力卡余额限制"
        return "约 ${AiStudioSchedules.trim(total / 60.0)} 小时（$reason）"
    }
}
