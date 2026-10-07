package com.local.comfyuimobile.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端命令包裹/提取单测（v0.2.97）。
 *
 * 这些用例是用 Python 按同一套逻辑先跑过真实形态的缓冲区、确认输出无误后才落成断言的
 * ——terminal 的输出解析不看实际数据几乎必错。
 *
 * 最容易被忽略的是**回显**：PTY 会把敲进去的那行原样吐回来，缓冲区里于是出现两次标记
 * （回显那次后面是字面 `$?`，结果那次后面是数字）。取第一次出现会拿到空输出。
 */
class TerminalShellTest {

    private val token = "ab12"
    private val mark = TerminalShell.marker(token)

    @Test
    fun wrapRunsCommandAndEchoesExitCode() {
        val wrapped = TerminalShell.wrap("ls ~/ComfyUI/models", token)
        assertTrue(wrapped, wrapped.startsWith("ls ~/ComfyUI/models; "))
        // $? 必须在双引号内（由 shell 展开），不能转义掉。
        assertTrue(wrapped, wrapped.endsWith("""echo "$mark:${'$'}?"""))
    }

    @Test
    fun extractsOutputAndExitCodeSkippingEcho() {
        val buffer =
            "ls ~/ComfyUI/models; echo \"$mark:\$?\"\r\n" +
                "checkpoints\r\nloras\r\n" +
                "$mark:0\r\n"
        val result = TerminalShell.extract(buffer, token)
        assertEquals("checkpoints\nloras", result.text)
        assertEquals(0, result.exitCode)
        assertTrue(result.finished)
    }

    @Test
    fun reportsNonZeroExitCode() {
        val buffer =
            "cat /nope; echo \"$mark:\$?\"\r\n" +
                "cat: /nope: No such file or directory\r\n" +
                "$mark:1\r\n"
        val result = TerminalShell.extract(buffer, token)
        assertEquals("cat: /nope: No such file or directory", result.text)
        assertEquals(1, result.exitCode)
    }

    @Test
    fun unfinishedCommandReportsNotFinished() {
        // 只有回显、没有结果标记：命令还在跑（或卡在交互式提示）。
        // 此时 finished 必须是 false，调用方要据此告诉模型"未结束"，
        // 不能让它把空输出当成命令结果。
        val result = TerminalShell.extract("sleep 100; echo \"$mark:\$?\"\r\n", token)
        assertFalse(result.finished)
        assertNull(result.exitCode)
    }

    @Test
    fun commandWithoutOutputStillReportsSuccess() {
        val result = TerminalShell.extract("cd ~; echo \"$mark:\$?\"\r\n$mark:0\r\n", token)
        assertEquals("", result.text)
        assertEquals(0, result.exitCode)
        assertTrue(result.finished)
    }

    @Test
    fun outputContainingMarkerTextDoesNotConfuse() {
        // 命令自己输出了形如标记的文本（但后面不是数字）——不能被当成结果。
        val buffer =
            "echo \"$mark:\$?\"; echo \"haha $mark:notnum\"; echo \"$mark:\$?\"\r\n" +
                "$mark:\$?\r\n" +
                "haha $mark:notnum\r\n" +
                "$mark:7\r\n"
        val result = TerminalShell.extract(buffer, token)
        assertEquals(7, result.exitCode)
        assertTrue(result.text, result.text.contains("notnum"))
    }

    @Test
    fun longOutputTruncatesKeepingTail() {
        val longOut = (1..5000).joinToString("\n") { "line$it" }
        val result = TerminalShell.extract("seq; echo \"$mark:\$?\"\r\n$longOut\r\n$mark:0\r\n", token)
        assertTrue("应说明已截断", result.text.contains("已截断"))
        // 结论通常在末尾，截断要保尾部。
        assertTrue(result.text, result.text.contains("line5000"))
        assertEquals(0, result.exitCode)
    }
}
