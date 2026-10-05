package com.local.comfyuimobile.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 端口解析单测（v0.2.89）。
 *
 * resolvePort 是 UI 与服务共用的唯一端口解析路径——曾经两边各写一份、服务那份
 * 把 0 直接传给 ServerSocket（语义是"系统随机分配"），导致首次开 MCP 就绑到
 * 随机端口、UI 却显示默认端口。这条测试锁住"0 必须落到默认端口"。
 */
class McpServerManagerTest {

    @Test
    fun zeroFallsBackToDefault() {
        // 0 = 用户从未配置。绝不能透传给 ServerSocket（那是"随机分配"）。
        assertEquals(McpServerManager.DEFAULT_PORT, McpServerManager.resolvePort(0))
    }

    @Test
    fun configuredPortPassesThrough() {
        assertEquals(23456, McpServerManager.resolvePort(23456))
        assertEquals(8765, McpServerManager.resolvePort(8765))
    }

    @Test
    fun privilegedPortIsInvalid() {
        // 低于 1024 是特权端口，普通 App 绑不上——当无效处理，回落默认。
        assertEquals(McpServerManager.DEFAULT_PORT, McpServerManager.resolvePort(80))
        assertEquals(McpServerManager.DEFAULT_PORT, McpServerManager.resolvePort(443))
    }

    @Test
    fun negativeAndOversizedAreInvalid() {
        assertEquals(McpServerManager.DEFAULT_PORT, McpServerManager.resolvePort(-1))
        assertEquals(McpServerManager.DEFAULT_PORT, McpServerManager.resolvePort(65536))
    }

    @Test
    fun defaultPortIsInValidRange() {
        // 防止以后改 DEFAULT_PORT 时改到非法区间（那会让默认配置直接失效）。
        assert(McpServerManager.DEFAULT_PORT in McpServerManager.MIN_PORT..McpServerManager.MAX_PORT)
    }
}
