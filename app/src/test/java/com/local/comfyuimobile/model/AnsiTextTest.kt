package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.66：终端转义序列剥离的单测。
 *
 * 锁住的是真机截图里的那串乱码：`ls` 的输出带颜色，App 直接把
 * `[0m[01;36mComfyUI[0m` 显示给了用户。
 */
class AnsiTextTest {

    private val esc = "\u001B"

    // ===== SGR 颜色（本次问题的正主）=====

    @Test
    fun stripsSgrColorSequences() {
        // 截图里的原样：ls 的彩色目录输出
        val raw = "${esc}[0m${esc}[01;34mComfyUI${esc}[0m  ${esc}[01;34mmodels${esc}[0m"
        assertEquals("ComfyUI  models", AnsiText.strip(raw))
    }

    @Test
    fun stripsMultiParameterSgr() {
        // 38;5;n（256 色）与 38;2;r;g;b（真彩）——现代 LS_COLORS 会用
        assertEquals("x", AnsiText.strip("${esc}[38;5;208mx${esc}[0m"))
        assertEquals("y", AnsiText.strip("${esc}[38;2;255;128;0my${esc}[0m"))
        assertEquals("bold", AnsiText.strip("${esc}[1mbold${esc}[22m"))
    }

    @Test
    fun stripsEmptyParameterSgr() {
        // ESC[m 是"重置"，参数为空——不能漏
        assertEquals("abc", AnsiText.strip("${esc}[mabc"))
    }

    // ===== 其它转义序列 =====

    @Test
    fun stripsCursorAndEraseSequences() {
        // 清行 ESC[K、光标移动 ESC[2A、ESC[H
        assertEquals("line", AnsiText.strip("${esc}[Kline${esc}[2A"))
        assertEquals("top", AnsiText.strip("${esc}[Htop"))
    }

    @Test
    fun stripsOscTitleAndHyperlink() {
        // OSC 以 BEL 或 ESC\ 结束
        assertEquals("hello", AnsiText.strip("${esc}]0;窗口标题\u0007hello"))
        assertEquals("link", AnsiText.strip("${esc}]8;;http://x${esc}\\link"))
    }

    @Test
    fun stripsTwoCharEscapes() {
        assertEquals("text", AnsiText.strip("${esc}Mtext"))
    }

    // ===== 保留有意义的内容 =====

    @Test
    fun keepsNewlinesAndTabs() {
        assertEquals("a\nb\tc", AnsiText.strip("a\nb\tc"))
    }

    @Test
    fun doesNotEatPlainBrackets() {
        // 关键：不能把普通文本里的方括号当转义序列吃掉
        assertEquals("arr[0] = 1", AnsiText.strip("arr[0] = 1"))
        // 整串原样返回——方括号在普通文本里只是普通字符
        assertEquals(
            "ps aux | grep -i \"[c]omfy\"",
            AnsiText.strip("ps aux | grep -i \"[c]omfy\""),
        )
    }

    @Test
    fun removesStrayControlCharacters() {
        // 孤立的 BEL / DEL 不该渲染成方块
        assertEquals("ok", AnsiText.strip("o\u0007k"))
        assertEquals("ok", AnsiText.strip("o\u007Fk"))
    }

    @Test
    fun carriageReturnBecomesLineBreak() {
        // \r 是"回到行首重写"（进度条）；保留会让同一行重复显示
        assertEquals("50%\n100%", AnsiText.strip("50%\r100%"))
    }

    // ===== tidy =====

    @Test
    fun tidyCollapsesRepeatedBlankLines() {
        assertEquals("a\n\nb", AnsiText.tidy("a\n\n\n\n\nb"))
    }

    @Test
    fun tidyTrimsTrailingWhitespaceAndBlankLines() {
        assertEquals("a\nb", AnsiText.tidy("a   \nb\n\n\n"))
    }

    @Test
    fun tidyRealisticListing() {
        val raw = "${esc}[0m${esc}[01;34mComfyUI${esc}[0m\n\n\n${esc}[01;34mmodels${esc}[0m\n\n"
        assertEquals("ComfyUI\n\nmodels", AnsiText.tidy(raw))
    }

    // ===== TerminalCommandResult.forDisplay =====

    @Test
    fun forDisplayStripsAnsiAndKeepsStructure() {
        val result = TerminalCommandResult(
            command = "ls ~",
            output = "${esc}[0m${esc}[01;36mComfyUI${esc}[0m\n${esc}[01;34mmodels${esc}[0m",
            exitCode = 0,
            success = true,
        )
        val shown = result.forDisplay()
        assertFalse("界面上不该出现转义字符", shown.contains(esc))
        assertFalse("也不该出现裸露的颜色码", shown.contains("[01;36m"))
        assertTrue(shown.contains("ComfyUI"))
        assertTrue(shown.contains("models"))
        // 状态要保留，便于用户对照；但**不回显命令**（上面已有"（已执行）xxx"气泡）
        assertTrue(shown.contains("成功"))
        assertFalse("命令不该重复出现", shown.contains("ls ~"))
    }

    @Test
    fun forDisplayTruncatesVeryLongOutput() {
        val huge = "x".repeat(50_000)
        val shown = TerminalCommandResult("cmd", huge, 0, true).forDisplay(maxChars = 100)
        assertTrue("超长输出要截断，否则列表会卡", shown.length < 500)
        assertTrue(shown.contains("已截断"))
    }

    @Test
    fun forDisplayMarksFailureAndEmptyOutput() {
        val failed = TerminalCommandResult("bad", "", 1, false).forDisplay()
        assertTrue(failed.contains("失败"))
        assertTrue(failed.contains("退出码 1"))
        assertTrue("无输出时要有明确说明", failed.contains("无输出"))
    }

    @Test
    fun forModelAlsoStripsAnsi() {
        // 颜色码对模型毫无意义，只会平白多占 token
        val result = TerminalCommandResult(
            command = "ls",
            output = "${esc}[01;36mx${esc}[0m",
            exitCode = 0,
            success = true,
        )
        assertFalse(result.forModel().contains(esc))
    }
}
