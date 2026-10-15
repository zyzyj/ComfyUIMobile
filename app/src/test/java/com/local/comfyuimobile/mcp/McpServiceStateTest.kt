package com.local.comfyuimobile.mcp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MCP 服务状态单测（v0.3.9）。
 *
 * 修的是真机反馈的「假打开」：关掉 MCP 再打开，开关显示开、通知栏没卡片。
 *
 * ## 根因（两条叠加）
 *
 * ① 用户点开时 `McpServerService.start()` 是**同步**发起的，而 enabled=true 的落盘是
 *    异步的 —— 服务启动时读磁盘，读到上一次的 false，立刻自停。
 * ② `lastStopReason` 守卫本意只管系统重建，却把用户的显式开启也拦了 ——
 *    所以一旦停过一次，用户**再也打不开**。
 *
 * 所以本测试盯两件事：**意图必须优先于磁盘**；**状态只由服务上报**。
 */
class McpServiceStateTest {

    @Before
    fun setUp() {
        McpServiceState.intent = null
        McpServiceState.markStopped()
    }

    @After
    fun tearDown() {
        McpServiceState.intent = null
        McpServiceState.markStopped()
    }

    // ===== 核心：意图优先于磁盘（修「假打开」）=====

    @Test
    fun intentWinsOverStaleDiskValue() {
        // 这就是那个竞态：磁盘还没写完（仍是 false），但用户已经点了开。
        McpServiceState.intent = true
        assertTrue("意图为开时必须能启动，不能读旧的磁盘值", McpServiceState.shouldRun(storedEnabled = false))
    }

    @Test
    fun intentOffWinsOverStaleDiskTrue() {
        // 反向同理：用户点了关，磁盘还写着 true（落盘异步），也不该启动。
        McpServiceState.intent = false
        assertFalse(McpServiceState.shouldRun(storedEnabled = true))
    }

    @Test
    fun fallsBackToDiskWhenNobodyHasExpressedIntent() {
        // 进程刚启动、还没进过 MCP 页：此时没有内存意图，只能信磁盘
        // （否则"开着开关重启 App 自动拉起"就失效了）。
        McpServiceState.intent = null
        assertTrue(McpServiceState.shouldRun(storedEnabled = true))
        assertFalse(McpServiceState.shouldRun(storedEnabled = false))
    }

    // ===== 状态只由服务上报 =====

    @Test
    fun startsStopped() {
        assertFalse(McpServiceState.isRunning())
        assertEquals(0, McpServiceState.runningPort())
    }

    @Test
    fun reportsRealBoundPort() {
        McpServiceState.markRunning(23456)
        assertTrue(McpServiceState.isRunning())
        assertEquals(23456, McpServiceState.runningPort())
    }

    @Test
    fun stoppedClearsPort() {
        // 停掉后端口必须归零：否则界面会拿一个已经不监听的端口显示"监听中"。
        McpServiceState.markRunning(23456)
        McpServiceState.markStopped()
        assertFalse(McpServiceState.isRunning())
        assertEquals(0, McpServiceState.runningPort())
    }

    @Test
    fun intentSurvivesStatusChanges() {
        // 意图与状态是两回事：服务停了不代表用户想关它。
        // 混在一起就会出现"服务被系统回收 → 开关自己变关"。
        McpServiceState.intent = true
        McpServiceState.markRunning(23456)
        McpServiceState.markStopped()
        assertEquals(true, McpServiceState.intent)
    }
}
