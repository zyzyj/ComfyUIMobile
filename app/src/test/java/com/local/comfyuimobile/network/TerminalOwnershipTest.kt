package com.local.comfyuimobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端归属登记单测（v0.3.8，P0-3）。
 *
 * 修的是"UI 接管了 AI 的终端"：`listTerminals` 返回项目里**所有** PTY、不分谁开的
 * （平台只给 name），而三处代码都写成 `firstOrNull() ?: createTerminal()`，
 * 于是 UI 主动连上 AI 先建的那条，还声明"我是界面在用"。
 *
 * 更危险的是「中断」：`sendRawInput` 不带 session 会落到"当前会话"，
 * 用户点 Ctrl+C **会打死 AI 正在跑的 ComfyUI**。
 */
class TerminalOwnershipTest {

    @Test
    fun uiOwnedNameIsReported() {
        TerminalOwnership.clearUi()
        TerminalOwnership.ownUi("5")
        assertEquals("5", TerminalOwnership.ui())
        TerminalOwnership.clearUi()
    }

    @Test
    fun switchingAccountClearsUiOwnership() {
        // 切账号后 UI 那条属于旧账号的项目，必须清——否则新账号下会拿旧名字去复用。
        TerminalOwnership.ownUi("7")
        TerminalOwnership.clearUi()
        assertEquals(null, TerminalOwnership.ui())
    }

    @Test
    fun aiNamesAreTrackedAndRemovable() {
        TerminalOwnership.clearAllMcp()
        TerminalOwnership.ownMcp("1")
        TerminalOwnership.ownMcp("2")
        assertEquals(setOf("1", "2"), TerminalOwnership.mcpSnapshot())
        TerminalOwnership.clearMcp("1")
        assertEquals(setOf("2"), TerminalOwnership.mcpSnapshot())
        TerminalOwnership.clearAllMcp()
        assertTrue(TerminalOwnership.mcpSnapshot().isEmpty())
    }

    @Test
    fun aiOwnedAmongExcludesUiOwnTerminal() {
        // 即使名字撞了（理论上不该），也不能把 UI 自己那条标成 AI 的——
        // 那会让用户以为自己看的是 AI 的终端。
        TerminalOwnership.clearAllMcp()
        TerminalOwnership.ownUi("1")
        TerminalOwnership.ownMcp("1")
        TerminalOwnership.ownMcp("2")
        val ai = TerminalOwnership.aiOwnedAmong(listOf("1", "2", "3"))
        assertEquals(listOf("2"), ai)
        TerminalOwnership.clearUi()
        TerminalOwnership.clearAllMcp()
    }

    @Test
    fun aiOwnedAmongIgnoresUnknownNames() {
        // 平台列表里有、但归属表没登记的（别人的/旧的）不算 AI 的。
        TerminalOwnership.clearAllMcp()
        TerminalOwnership.ownMcp("9")
        assertEquals(listOf("9"), TerminalOwnership.aiOwnedAmong(listOf("9", "42")))
        TerminalOwnership.clearAllMcp()
    }
}
