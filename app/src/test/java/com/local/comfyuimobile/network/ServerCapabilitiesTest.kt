package com.local.comfyuimobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerCapabilitiesTest {

    private lateinit var caps: ServerCapabilities
    private val server = "https://aistudio.baidu.com/xxx/8188"
    private val other = "http://192.168.1.9:8188"
    private val now = 1_000_000_000_000L

    @Before
    fun setUp() {
        caps = ServerCapabilities()
    }

    @Test
    fun assumesSupportUntilProvenOtherwise() {
        assertTrue("未知能力应乐观假设支持", caps.isSupported(server, ServerCapabilities.Capability.USERDATA))
        assertTrue("未失败过就不该受退避限制", caps.shouldRetry(server, ServerCapabilities.Capability.USERDATA, now))
    }

    @Test
    fun unsupportedCapabilityEntersBackoff() {
        caps.markFailure(server, ServerCapabilities.Capability.USERDATA, now, "HTTP 404", unsupported = true)
        assertFalse(caps.isSupported(server, ServerCapabilities.Capability.USERDATA))
        assertFalse(
            "刚失败就重试，应被退避挡住",
            caps.shouldRetry(server, ServerCapabilities.Capability.USERDATA, now + 5_000),
        )
    }

    @Test
    fun retryIsAllowedAfterBackoffExpires() {
        caps.markFailure(server, ServerCapabilities.Capability.USERDATA, now, "HTTP 404", unsupported = true)
        val delay = caps.nextDelayMs(1)
        assertTrue(
            "退避期过后应允许重试",
            caps.shouldRetry(server, ServerCapabilities.Capability.USERDATA, now + delay + 1),
        )
    }

    @Test
    fun backoffGrowsExponentiallyAndIsCapped() {
        assertEquals(30_000L, caps.nextDelayMs(1))
        assertEquals(60_000L, caps.nextDelayMs(2))
        assertEquals(120_000L, caps.nextDelayMs(3))
        assertEquals(240_000L, caps.nextDelayMs(4))
        assertEquals(480_000L, caps.nextDelayMs(5))
        assertEquals("封顶 10 分钟，避免永远不再尝试", 600_000L, caps.nextDelayMs(6))
        assertEquals(600_000L, caps.nextDelayMs(50))
    }

    @Test
    fun repeatedFailuresLengthenBackoff() {
        caps.markFailure(server, ServerCapabilities.Capability.USERDATA, now, "404", true)
        val first = caps.reason(server, ServerCapabilities.Capability.USERDATA)
        assertEquals(1, caps.failureCount(server, ServerCapabilities.Capability.USERDATA))
        caps.markFailure(server, ServerCapabilities.Capability.USERDATA, now + 31_000, "404", true)
        assertEquals(2, caps.failureCount(server, ServerCapabilities.Capability.USERDATA))
        assertTrue(caps.nextDelayMs(2) > caps.nextDelayMs(1))
        assertEquals("404", first)
    }

    @Test
    fun successRestoresOptimism() {
        caps.markFailure(server, ServerCapabilities.Capability.USERDATA, now, "404", true)
        assertFalse(caps.isSupported(server, ServerCapabilities.Capability.USERDATA))
        caps.markSuccess(server, ServerCapabilities.Capability.USERDATA)
        assertTrue("接口恢复后应重新乐观", caps.isSupported(server, ServerCapabilities.Capability.USERDATA))
        assertEquals(0, caps.failureCount(server, ServerCapabilities.Capability.USERDATA))
    }

    @Test
    fun transientNetworkErrorsDoNotDisableCapability() {
        // 超时、连接中断这类是暂时故障，不该把接口永久标记成不支持
        caps.markFailure(server, ServerCapabilities.Capability.HISTORY, now, "timeout", unsupported = false)
        assertTrue("暂时故障不应禁用接口", caps.isSupported(server, ServerCapabilities.Capability.HISTORY))
        assertTrue("暂时故障不应触发退避", caps.shouldRetry(server, ServerCapabilities.Capability.HISTORY, now + 1))
        assertEquals(1, caps.failureCount(server, ServerCapabilities.Capability.HISTORY))
    }

    @Test
    fun capabilitiesAreIsolatedPerServer() {
        caps.markFailure(server, ServerCapabilities.Capability.USERDATA, now, "404", true)
        assertFalse(caps.isSupported(server, ServerCapabilities.Capability.USERDATA))
        assertTrue("换台服务器应互不影响", caps.isSupported(other, ServerCapabilities.Capability.USERDATA))
    }

    @Test
    fun resetClearsOnlyThatServer() {
        caps.markFailure(server, ServerCapabilities.Capability.USERDATA, now, "404", true)
        caps.markFailure(other, ServerCapabilities.Capability.HISTORY, now, "404", true)
        caps.reset(server)
        assertTrue(caps.isSupported(server, ServerCapabilities.Capability.USERDATA))
        assertFalse(caps.isSupported(other, ServerCapabilities.Capability.HISTORY))
    }

    @Test
    fun summarizesUnavailableFeaturesForUsers() {
        caps.markFailure(server, ServerCapabilities.Capability.USERDATA, now, "404", true)
        caps.markFailure(server, ServerCapabilities.Capability.HISTORY, now, "404", true)
        val summary = caps.unavailableSummary(server)
        assertTrue(summary.contains("云端工作流"))
        assertTrue(summary.contains("历史记录"))
        assertFalse(summary.contains("节点定义"))
        assertEquals("", caps.unavailableSummary("unknown-server"))
    }
}
