package com.local.comfyuimobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端归属单测（v0.2.98，修 P0-2）。
 *
 * P0-2 的现象：AI 用过终端后，用户在控制台敲的命令**发到了 AI 的终端**——
 * 自己那条永远没输出。根因是 `openTerminal` 无条件抢占"当前终端"。
 *
 * 这个 bug 能溜过原有 65 个测试，是因为**没有任何测试覆盖"谁是当前终端"**。
 * 这里把不变式锁死：**只有显式 setUi 才能改"界面在用的那条"**。
 */
class TerminalRegistryTest {

    private fun session(name: String) = AiStudioKernelClient.TerminalSession(name, "conn-$name", null)

    @Test
    fun uiStationStaysWhenAnotherTerminalIsOpened() {
        // 这是 P0-2 的核心不变式。
        val registry = TerminalRegistry()
        val ui = session("1")
        registry.put(ui)
        registry.setUi(ui)

        // 模拟 AI 开了一条自己的终端（不调用 setUi）。
        val ai = session("default")
        registry.put(ai)

        assertSame("界面那条不该被顶掉", ui, registry.uiSession())
        assertEquals("1", registry.uiSession()?.name)
        // 但两条都在，都能按名取到。
        assertSame(ai, registry.get("default"))
    }

    @Test
    fun setUiExplicitlySwitches() {
        val registry = TerminalRegistry()
        val first = session("1")
        val second = session("2")
        registry.put(first)
        registry.put(second)
        registry.setUi(first)
        registry.setUi(second)
        assertSame(second, registry.uiSession())
    }

    @Test
    fun removingUiSessionClearsUiPointer() {
        // 界面那条断了，指针要跟着清——否则 currentSession() 会取到已关闭的会话。
        val registry = TerminalRegistry()
        val ui = session("1")
        registry.put(ui)
        registry.setUi(ui)
        registry.remove("1")
        assertNull(registry.uiSession())
    }

    @Test
    fun removingOtherSessionKeepsUiPointer() {
        val registry = TerminalRegistry()
        val ui = session("1")
        val ai = session("default")
        registry.put(ui)
        registry.put(ai)
        registry.setUi(ui)
        registry.remove("default")
        assertSame(ui, registry.uiSession())
    }

    @Test
    fun removeIfSameIgnoresStaleDetach() {
        // 重连会先建新会话，旧 socket 的回调迟到；不比对实例的话，
        // 旧回调的 detach 会把刚连上的新会话一起删掉。
        val registry = TerminalRegistry()
        val old = session("1")
        registry.put(old)
        registry.setUi(old)

        val fresh = session("1")
        registry.put(fresh) // 新会话覆盖同名
        registry.removeIfSame(old) // 旧会话的迟到回调

        assertSame("新会话不能被旧回调删掉", fresh, registry.get("1"))
        assertSame("界面指针不应被误清", fresh, registry.uiSession())
    }

    @Test
    fun clearDropsEverythingAndMarksClosed() {
        val registry = TerminalRegistry()
        val a = session("1")
        val b = session("default")
        registry.put(a)
        registry.put(b)
        registry.setUi(a)
        registry.clear()
        assertTrue(registry.names().isEmpty())
        assertNull(registry.uiSession())
        assertTrue("会话应被标记关闭", a.closed && b.closed)
    }

    @Test
    fun namesListsAllSessions() {
        val registry = TerminalRegistry()
        registry.put(session("1"))
        registry.put(session("default"))
        assertEquals(setOf("1", "default"), registry.names().toSet())
    }
}
