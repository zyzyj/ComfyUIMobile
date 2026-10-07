package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「ComfyUI 为什么不可用」单测（v0.2.97）。
 *
 * 这层挡的是**误导性提示**：原先一切非 CONNECTED 都显示"请回工作流页连接服务器"，
 * 而项目根本没启动时，让用户去刷新 Cookie、重启实例全是白费——平台侧还会把
 * "项目未启动"报成"需要登录"，更容易把人带偏。规划书 §六 要求区分四种原因，
 * 因为每种要采取的行动都不同。
 */
class ComfyAvailabilityTest {

    private fun resolve(
        connected: Boolean = false,
        hasServer: Boolean = true,
        connecting: Boolean = false,
        loginExpired: Boolean = false,
        runningProjects: Int = 0,
        startingProjects: Int = 0,
        environmentReady: Boolean = false,
    ) = ComfyAvailability.resolve(
        connected, hasServer, connecting, loginExpired,
        runningProjects, startingProjects, environmentReady,
    )

    @Test
    fun connectedAlwaysWins() {
        // 已连上就是就绪：下面的判据都是"连不上时怎么解释"，不该覆盖事实。
        // （比如项目状态还没刷到 running，但 ComfyUI 确实通了。）
        assertEquals(
            ComfyAvailability.Reason.READY,
            resolve(connected = true, runningProjects = 0, hasServer = false),
        )
        assertFalse(ComfyAvailability.isBlocking(ComfyAvailability.Reason.READY))
    }

    @Test
    fun noRunningProjectSaysProjectNotStarted() {
        // 这是最容易被误报成"要登录"的一种，必须说成"项目没跑"。
        val reason = resolve(runningProjects = 0, startingProjects = 0)
        assertEquals(ComfyAvailability.Reason.PROJECT_NOT_RUNNING, reason)
        val text = ComfyAvailability.describe(reason)
        assertTrue(text, text.contains("项目"))
        // 必须给出行动，不然用户只能猜。
        assertTrue(text, text.contains("启动"))
    }

    @Test
    fun submittingProjectSaysAllocating() {
        val reason = resolve(runningProjects = 0, startingProjects = 1)
        assertEquals(ComfyAvailability.Reason.PROJECT_STARTING, reason)
        assertTrue(ComfyAvailability.describe(reason).contains("分配"))
    }

    @Test
    fun runningButEnvironmentNotReadySaysRunStartupScript() {
        // 项目在跑、但 8188 不可达：最常见的下一步是"去跑启动脚本"。
        val reason = resolve(runningProjects = 1, environmentReady = false)
        assertEquals(ComfyAvailability.Reason.ENVIRONMENT_NOT_READY, reason)
        val text = ComfyAvailability.describe(reason)
        assertTrue(text, text.contains("启动脚本") || text.contains("8188"))
    }

    @Test
    fun loginExpiryIsReportedAsItself() {
        val reason = resolve(loginExpired = true, runningProjects = 1, environmentReady = true)
        assertEquals(ComfyAvailability.Reason.LOGIN_EXPIRED, reason)
        assertTrue(ComfyAvailability.describe(reason).contains("重新登录"))
    }

    @Test
    fun noConfiguredServerIsItsOwnReason() {
        val reason = resolve(hasServer = false)
        assertEquals(ComfyAvailability.Reason.NO_SERVER, reason)
    }

    @Test
    fun connectingIsReported() {
        // 项目在跑、环境就绪、正在重连：应显示连接中而不是"项目没启动"。
        val reason = resolve(runningProjects = 1, environmentReady = true, connecting = true)
        assertEquals(ComfyAvailability.Reason.CONNECTING, reason)
    }

    @Test
    fun everyBlockingReasonHasActionableText() {
        // 所有"不可用"的文案都不该只是"不可用"三个字——必须带一句该做什么。
        val blocking = ComfyAvailability.Reason.entries.filter { ComfyAvailability.isBlocking(it) }
        assertTrue("应至少覆盖四种原因", blocking.size >= 4)
        blocking.forEach { reason ->
            val text = ComfyAvailability.describe(reason)
            assertTrue("$reason 的文案太短：$text", text.length > 20)
        }
    }
}
