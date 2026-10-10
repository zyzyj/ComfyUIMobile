package com.local.comfyuimobile.network

import com.local.comfyuimobile.data.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 平台风控退避重试（v0.3.7 从 `MainViewModel` 抽出）。
 *
 * ## 为什么必须抽出共用
 *
 * 界面侧的 `aiStudioStartProject` 一直走 `withRiskRetry`（1s→3s→8s→20s），
 * 而 MCP 侧的 `AiStudioBridge.startGpu` **一次都不重试**——那是本项目第 4 条
 * 平行路径。而 [AiStudioRiskControl] 的注释自己写着「8407 是可恢复的短暂状态，
 * 退避重试比抛错给用户有用得多」。
 *
 * 两处各写一套退避，迟早又只改一处。所以抽到这里，两边共用同一个序列与判定。
 *
 * @param action 用于日志与提示文案的动作名（如"启动环境"）
 * @param onWaiting 每次退避前回调（界面用它把"N 秒后自动重试"写进 UI）
 */
internal object AiStudioRetry {

    /**
     * @param action 动作名（写日志与文案）
     * @param onWaiting 每次退避前回调，参数是给用户/模型看的话
     * @param block 实际请求
     */
    suspend fun <T> withRiskRetry(
        action: String,
        onWaiting: (String) -> Unit = {},
        block: suspend () -> T,
    ): T {
        var attempt = 0
        while (true) {
            val outcome = runCatching { block() }
            val error = outcome.exceptionOrNull()
            if (error == null) return outcome.getOrThrow()
            if (error is CancellationException) throw error
            val code = (error as? AiStudioException)?.errorCode
            val delayMs = AiStudioRiskControl.delayForAttempt(attempt)
            // 只对风控/限流退避。参数错误、未登录（403）重试没意义，反而加刷风控。
            if (!AiStudioRiskControl.isRetryable(code) || delayMs == null) throw error
            val message = AiStudioRiskControl.messageFor(code, delayMs)
            AppLogger.warn("$action 被平台风控拦下（码 $code），${delayMs / 1000} 秒后重试")
            onWaiting(message)
            delay(delayMs)
            attempt++
        }
    }
}
