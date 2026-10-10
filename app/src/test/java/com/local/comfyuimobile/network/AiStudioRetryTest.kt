package com.local.comfyuimobile.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退避重试包装单测（v0.3.7，文档 §3.5）。
 *
 * 存在的理由：MCP 侧 `startGpu` 以前**一次都不重试**，而界面侧一直走退避——
 * 那是本项目第 4 条平行路径。抽出 [AiStudioRetry] 后两边共用，这些用例保护
 * 它的两条边界：可重试的要真重试、不可重试的**一次都不能重试**。
 */
class AiStudioRetryTest {

    @Test
    fun returnsImmediatelyOnSuccess() = runBlocking {
        var calls = 0
        val result = AiStudioRetry.withRiskRetry("测试") {
            calls++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun doesNotRetryNonRetryableError() = runBlocking {
        // 403 / 参数错误重试没意义，反而加刷风控——必须一次就抛。
        var calls = 0
        val error = runCatching {
            AiStudioRetry.withRiskRetry("测试") {
                calls++
                throw AiStudioException("登录失效", errorCode = 403)
            }
        }.exceptionOrNull()
        assertEquals(1, calls)
        assertTrue(error is AiStudioException)
    }

    @Test
    fun retriesRiskControlError() = runBlocking {
        // 8407 是可恢复的短暂状态：第一次失败要退避后重试（首次间隔 1 秒）。
        // 真等 1 秒是可以接受的——这条断言的价值远大于 1 秒成本。
        var calls = 0
        val result = AiStudioRetry.withRiskRetry("测试") {
            calls++
            if (calls == 1) throw AiStudioException("风控", errorCode = 8407)
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals("应重试一次", 2, calls)
    }

    @Test
    fun onWaitingReceivesActionableMessage() = runBlocking {
        val messages = mutableListOf<String>()
        runCatching {
            AiStudioRetry.withRiskRetry("启动环境", onWaiting = { messages += it }) {
                throw AiStudioException("风控", errorCode = 8407)
            }
        }
        assertTrue("每次退避前都要回调", messages.isNotEmpty())
        assertTrue("文案应说明会自动重试：$messages", messages.first().contains("自动重试"))
    }
}
