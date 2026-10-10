package com.local.comfyuimobile.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台执行包装单测（v0.3.7，P0-2）。
 *
 * 背景：真机实证一条 34 分钟的命令超时后，AiCode 的 agent 循环**直接终止整轮任务**，
 * 剩下 39 个任务一个都没跑。所以长任务必须能"提交完立刻返回"。
 */
class TerminalBackgroundTest {

    @Test
    fun wrapUsesNohupAndRedirectsOutput() {
        val wrapped = TerminalBackground.wrap("python train.py", "/tmp/mcp-bg/t.log")
        assertTrue("必须 nohup（否则会话断开时子进程被 SIGHUP 杀掉）", wrapped.contains("nohup"))
        assertTrue(wrapped.contains("> /tmp/mcp-bg/t.log 2>&1"))
        assertTrue("要能拿到 PID", wrapped.contains("echo \$!"))
    }

    @Test
    fun wrapCreatesLogDir() {
        val wrapped = TerminalBackground.wrap("ls", "/tmp/mcp-bg/x.log")
        assertTrue(wrapped.contains("mkdir -p ${TerminalBackground.LOG_DIR}"))
    }

    @Test
    fun wrapStripsTrailingAmpersand() {
        // AI 常写成 `cmd &`；再包一层会变成 `cmd & ... &` —— 后者语义混乱。
        val wrapped = TerminalBackground.wrap("bash start.sh &", "/tmp/x.log")
        assertTrue(!wrapped.contains("start.sh &"))
        assertTrue(wrapped.contains("start.sh"))
    }

    @Test
    fun shellQuoteEscapesSingleQuotes() {
        // 安全边界：命令来自模型。不转义的话内层单引号会提前闭合包裹、外层 shell 会
        // 展开命令里的 $ 或反引号——轻则跑错，重则命令注入。
        assertEquals("'a'\\''b'", TerminalBackground.shellQuote("a'b"))
    }

    @Test
    fun shellQuoteNeutralizesCommandSubstitution() {
        val quoted = TerminalBackground.shellQuote("\$(rm -rf /)")
        // 整段被单引号包住 → 命令替换不会被外层 shell 执行。
        assertTrue(quoted.startsWith("'") && quoted.endsWith("'"))
        assertTrue(quoted.contains("\$(rm -rf /)"))
    }

    @Test
    fun logPathIsSanitized() {
        // 终端名可能来自模型，不能直接拼进路径（否则能跳到别的目录）。
        val path = TerminalBackground.logPathFor("../../etc", 123L)
        assertTrue(path.startsWith(TerminalBackground.LOG_DIR + "/"))
        assertTrue(!path.contains(".."))
    }

    @Test
    fun logPathFallsBackToDefaultForBlankName() {
        assertTrue(TerminalBackground.logPathFor("", 1L).endsWith("/default-1.log"))
        assertTrue(TerminalBackground.logPathFor("!!!", 1L).endsWith("/default-1.log"))
    }

    @Test
    fun parsePidHandlesCrLf() {
        // PTY 会带回车：输出可能是 "12345\r\n"。
        assertEquals(12345L, TerminalBackground.parsePid("12345\r\n"))
    }

    @Test
    fun parsePidReturnsNullWhenAbsent() {
        // 拿不到就返回 null —— 不要编一个假 PID。
        assertNull(TerminalBackground.parsePid(""))
        assertNull(TerminalBackground.parsePid("bash: command not found"))
    }

    @Test
    fun parsePidTakesFirstNumberLine() {
        assertEquals(777L, TerminalBackground.parsePid("noise\r\n777\r\n"))
    }
}
