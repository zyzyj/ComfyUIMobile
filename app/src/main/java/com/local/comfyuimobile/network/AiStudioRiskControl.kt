package com.local.comfyuimobile.network

/**
 * 平台风控（8407）的退避策略（v0.2.94）。
 *
 * ## 为什么需要它
 *
 * 真机日志（用户反馈「只能默认档位启动」，v0.2.92）里，`listSchedules`（读 GPU 档位）
 * 与 `startProject`（启动）会被平台回 **8407「账号存在安全风险」**，而同期的
 * 「积分 / 项目列表 / 算力卡」这类接口完全正常。表现就是**档位列表空 → 只能默认档**，
 * 用户过一会儿再试又能成功。
 *
 * 之前对这种错误一律「失败即报错」，用户只能自己去浏览器做人机验证、或者干等。
 * 但**它是可恢复的短暂状态**：退避重试比抛错给用户有用得多。
 *
 * ## 为什么单列一个纯函数
 *
 * 重试间隔、次数、哪些错误码算可重试——这些判定在**读档位**与**启动**两处都要用。
 * 分开写会像 `resolvePort` 那次一样，只改一处、另一处静默失效。
 *
 * 注意：**只对风控/限流类错误退避**。参数错误、未登录（403）之类重试没意义，
 * 重试反而增加风控风险。
 */
object AiStudioRiskControl {

    /** 平台判定账号有安全风险（需人机验证/请求过频）。 */
    const val CODE_RISK = 8407

    /** 需要图形验证码——同属"稍后再试可能就好了"的一类。 */
    const val CODE_CAPTCHA = 8307

    /** 退避序列（毫秒）：1s → 3s → 8s → 20s。总等待约 32 秒，够跨过短暂冷却。 */
    val BACKOFF_MILLIS = longArrayOf(1_000L, 3_000L, 8_000L, 20_000L)

    /** 该错误码是否值得退避重试。 */
    fun isRetryable(errorCode: Int?): Boolean =
        errorCode == CODE_RISK || errorCode == CODE_CAPTCHA

    /** 第 attempt 次失败后应等多久（attempt 从 0 起）；用完序列返回 null 表示不再重试。 */
    fun delayForAttempt(attempt: Int): Long? = BACKOFF_MILLIS.getOrNull(attempt)

    /**
     * 给用户看的话。要**明确说清"会自动重试"**——否则用户看到"失败"两个字就去
     * 反复手动点，那只会把风控拖得更久。
     */
    fun messageFor(errorCode: Int?, nextDelayMillis: Long?): String = when {
        errorCode == CODE_RISK && nextDelayMillis != null ->
            "平台判定账号需安全验证（错误码 8407），${nextDelayMillis / 1000} 秒后自动重试；" +
                "若一直失败，请到浏览器打开 AI Studio 完成一次人机验证"
        errorCode == CODE_RISK ->
            "平台判定账号需安全验证（错误码 8407），已重试多次仍未通过。" +
                "请到浏览器打开 AI Studio 完成一次人机验证后，点「重试读取档位」"
        errorCode == CODE_CAPTCHA && nextDelayMillis != null ->
            "平台要求图形验证码，${nextDelayMillis / 1000} 秒后自动重试"
        else -> "平台要求完成验证后继续操作"
    }
}
