package com.local.comfyuimobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 风控退避策略单测（v0.2.94）。
 *
 * 对应真机事故：`listSchedules` / `startProject` 被平台回 8407，而同一时刻
 * 「积分 / 项目列表 / 算力卡」全部正常——用户看到的是「档位列表空、只能默认档」。
 * 它是可恢复的短暂状态，退避重试比直接报错有用。
 *
 * 测试重点是**可重试与不可重试的边界**：把不该重试的（参数错、登录失效）也重试，
 * 只会加重风控。
 */
class AiStudioRiskControlTest {

    @Test
    fun riskAndCaptchaAreRetryable() {
        assertTrue(AiStudioRiskControl.isRetryable(8407))
        assertTrue(AiStudioRiskControl.isRetryable(8307))
    }

    @Test
    fun ordinaryErrorsAreNotRetryable() {
        // 参数错误 / 登录失效 / 无错误码：重试没意义，反而加刷风控。
        assertFalse(AiStudioRiskControl.isRetryable(403))
        assertFalse(AiStudioRiskControl.isRetryable(10004))
        assertFalse(AiStudioRiskControl.isRetryable(null))
    }

    @Test
    fun backoffIsIncreasingAndBounded() {
        val delays = AiStudioRiskControl.BACKOFF_MILLIS
        assertEquals("首次应等 1 秒", 1_000L, delays.first())
        assertTrue("间隔应递增", delays.toList().zipWithNext().all { (a, b) -> b > a })
        assertTrue("总等待应在 1 分钟内（用户等得起）", delays.sum() < 60_000L)
    }

    @Test
    fun delayFollowsAttemptIndex() {
        assertEquals(1_000L, AiStudioRiskControl.delayForAttempt(0))
        assertEquals(3_000L, AiStudioRiskControl.delayForAttempt(1))
        assertNotNull(AiStudioRiskControl.delayForAttempt(AiStudioRiskControl.BACKOFF_MILLIS.size - 1))
    }

    @Test
    fun delayIsNullAfterSequenceExhausted() {
        // 用完序列要明确返回 null：调用方据此放弃重试，而不是无限循环。
        assertNull(AiStudioRiskControl.delayForAttempt(AiStudioRiskControl.BACKOFF_MILLIS.size))
        assertNull(AiStudioRiskControl.delayForAttempt(99))
    }

    @Test
    fun messageMentionsAutoRetryWhileWaiting() {
        // 文案必须说清"会自动重试"——否则用户看到"失败"就去反复手点，把风控拖更久。
        val waiting = AiStudioRiskControl.messageFor(8407, 3_000L)
        assertTrue("应说明会自动重试", waiting.contains("自动重试"))
        assertTrue("应给出秒数", waiting.contains("3"))
    }

    @Test
    fun messageForExhaustedSaysWhatToDo() {
        val exhausted = AiStudioRiskControl.messageFor(8407, null)
        assertTrue("应给出可操作指引", exhausted.contains("人机验证"))
        assertTrue("不应再提自动重试", !exhausted.contains("自动重试"))
    }
}
