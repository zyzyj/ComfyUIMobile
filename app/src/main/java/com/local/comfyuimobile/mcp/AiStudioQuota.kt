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
}
