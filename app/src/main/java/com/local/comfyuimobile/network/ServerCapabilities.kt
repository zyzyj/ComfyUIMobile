package com.local.comfyuimobile.network

/**
 * 记录"这台服务器到底支持哪些 ComfyUI 接口"，并据此决定还该不该重试。
 *
 * 起因：百度 AI Studio 的反向代理不支持 /userdata，App 却每 11 秒刷一次，
 * 17 分钟刷了 56 次，每次还把一整页 HTML 写进诊断日志。用户既看不到有用的
 * 信息，流量和时间也全耗在注定失败的请求上。
 *
 * 策略：
 *  - 默认乐观（假设支持），失败一次才降级；
 *  - 判定为"平台不支持"后进入指数退避，而不是继续定时刷；
 *  - 成功一次立即恢复乐观，避免误伤（比如服务器后来补上了接口）；
 *  - 按 serverKey 隔离，切换服务器互不影响。
 *
 * 纯 Kotlin，可单测（时间由调用方传入，不直接读系统时钟）。
 */
class ServerCapabilities {

    enum class Capability(val label: String) {
        USERDATA("云端工作流"),
        HISTORY("历史记录"),
        OBJECT_INFO("节点定义"),
        FEATURES("特性接口"),
        VIEW("图片读取"),
    }

    data class State(
        val supported: Boolean = true,
        /** 连续失败次数，用于退避。 */
        val attempt: Int = 0,
        /** 下次允许重试的时间戳（毫秒）。 */
        val nextRetryAt: Long = 0L,
        val reason: String? = null,
    )

    private val lock = Any()
    private val states = HashMap<String, HashMap<Capability, State>>()

    /** 未知即视为支持，保证不因为一次历史失败而永久禁用。 */
    fun isSupported(serverKey: String, capability: Capability): Boolean =
        synchronized(lock) { states[serverKey]?.get(capability)?.supported ?: true }

    /** 当前时刻是否值得再试一次（已过退避期，或从未失败）。 */
    fun shouldRetry(serverKey: String, capability: Capability, nowMs: Long): Boolean =
        synchronized(lock) {
            val state = states[serverKey]?.get(capability) ?: return@synchronized true
            if (state.supported) true else nowMs >= state.nextRetryAt
        }

    fun reason(serverKey: String, capability: Capability): String? =
        synchronized(lock) { states[serverKey]?.get(capability)?.reason }

    fun failureCount(serverKey: String, capability: Capability): Int =
        synchronized(lock) { states[serverKey]?.get(capability)?.attempt ?: 0 }

    /**
     * 记录一次失败。
     * @param unsupported 平台层面不支持（如返回网页错误页）→ 进入退避
     */
    fun markFailure(
        serverKey: String,
        capability: Capability,
        nowMs: Long,
        reason: String,
        unsupported: Boolean,
    ): State = synchronized(lock) {
        val map = states.getOrPut(serverKey) { HashMap() }
        val previous = map[capability]
        val attempt = (previous?.attempt ?: 0) + 1
        val next = State(
            supported = if (unsupported) false else true,
            attempt = attempt,
            // 不支持的接口走退避；普通网络错误仍保持可用状态，只是累计次数
            nextRetryAt = if (unsupported) nowMs + nextDelayMs(attempt) else 0L,
            reason = reason,
        )
        map[capability] = next
        next
    }

    /** 成功一次就恢复乐观，并清空退避。 */
    fun markSuccess(serverKey: String, capability: Capability) = synchronized(lock) {
        states.getOrPut(serverKey) { HashMap() }[capability] = State()
    }

    /** 手动重试 / 切换服务器时清空某台服务器的记录。 */
    fun reset(serverKey: String) = synchronized(lock) { states.remove(serverKey) }

    fun resetAll() = synchronized(lock) { states.clear() }

    /**
     * 指数退避：30s → 60s → 2m → 4m → 8m → 10m（封顶）。
     * 目的是让"注定失败"的请求逐渐安静，而不是每 10 秒刷一次。
     */
    fun nextDelayMs(attempt: Int): Long {
        // attempt=1 表示"第一次失败"，此时等 30 秒；之后每次翻倍。
        val step = (attempt - 1).coerceIn(0, 5)
        val raw = BASE_DELAY_MS shl step
        return raw.coerceAtMost(MAX_DELAY_MS)
    }

    /** 给用户的提示：哪些功能不可用。 */
    fun unavailableSummary(serverKey: String): String = synchronized(lock) {
        val map = states[serverKey] ?: return@synchronized ""
        map.entries.filter { !it.value.supported }
            .joinToString("、") { it.key.label }
    }

    private companion object {
        const val BASE_DELAY_MS = 30_000L
        const val MAX_DELAY_MS = 600_000L
    }
}
