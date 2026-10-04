package com.local.comfyuimobile.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.83：命令白名单的匹配规则 + **它不得绕过灾难熔断**。
 *
 * 第二块是本文件存在的主要理由：白名单是"用户信任声明"，不是"绕过安全边界的开关"。
 * 一旦匹配写松（把 glob 当正则、或让复合命令整条命中），会静默放行危险命令。
 */
class CommandAllowlistTest {

    private val defaults = CommandAllowlist.DEFAULT_PATTERNS

    // ===== 预置模式命中 =====

    @Test
    fun matchesDefaultReadOnlyCommands() {
        assertTrue(CommandAllowlist.matches("nvidia-smi", defaults))
        assertTrue(CommandAllowlist.matches("df -h ~", defaults))
        assertTrue(CommandAllowlist.matches("free -h", defaults))
        assertTrue(CommandAllowlist.matches("ls", defaults))
        assertTrue(CommandAllowlist.matches("ls -la ~/models", defaults))
        assertTrue(CommandAllowlist.matches("ps aux", defaults))
        assertTrue(CommandAllowlist.matches("cat ~/notes.txt", defaults))
    }

    @Test
    fun pipedCommandIsNotTrustedEvenIfVerbMatches() {
        // `ps aux*` 预置模式**不该**命中 `ps aux | grep python`——它含管道（复合命令），
        // 命中就等于放行整条链。这类要走正常确认。
        assertFalse(CommandAllowlist.matches("ps aux | grep python", defaults))
    }

    @Test
    fun doesNotMatchUnrelatedCommands() {
        assertFalse(CommandAllowlist.matches("pip install torch", defaults))
        assertFalse(CommandAllowlist.matches("git clone https://x/y.git", defaults))
        assertFalse(CommandAllowlist.matches("rm -rf ~/models", defaults))
    }

    // ===== 整条匹配：复合命令不得整条命中 =====

    @Test
    fun compoundCommandDoesNotMatchSinglePattern() {
        // `ls *` 命中 `ls -la`，但**不能**命中 `ls -la && rm -rf /`。
        // 否则白名单会变成"只要以 ls 开头就放行后面的任何东西"。
        assertFalse(CommandAllowlist.matches("ls -la && rm -rf ~/models", defaults))
        assertFalse(CommandAllowlist.matches("nvidia-smi; shutdown -h now", defaults))
        assertFalse(CommandAllowlist.matches("cat x.txt | tee /etc/passwd", defaults))
    }

    // ===== 安全边界：白名单不得让灾难命令免于确认 =====

    @Test
    fun allowlistCannotBypassCatastrophicFuse() {
        // 用户错误地把 `rm *` 加进白名单，灾难性命令仍必须需要确认（autoRunnable=false）。
        val badTrust = defaults + listOf("rm *", "*")
        assertTrue(
            "灾难命令即使命中白名单也必须确认",
            TerminalCommandSafety.requiresConfirmation("rm -rf /", 3, badTrust),
        )
        assertFalse(
            "灾难命令永远不可自动执行",
            TerminalCommandSafety.autoRunnable("rm -rf /", 3, badTrust),
        )
        assertTrue(TerminalCommandSafety.requiresConfirmation("reboot", 3, badTrust))
        assertFalse(TerminalCommandSafety.autoRunnable("reboot", 3, badTrust))
    }

    @Test
    fun allowlistCannotBypassCatastrophicInCompoundCommand() {
        val badTrust = listOf("*")
        assertTrue(TerminalCommandSafety.requiresConfirmation("nvidia-smi && mkfs.ext4 /dev/sda", 3, badTrust))
        assertFalse(TerminalCommandSafety.autoRunnable("nvidia-smi && mkfs.ext4 /dev/sda", 3, badTrust))
    }

    // ===== 白名单确实能省掉确认（这是它的用途）=====

    @Test
    fun trustedCommandSkipsConfirmationEvenAtStrictestLevel() {
        // 1 级「每条都问」下，命中白名单的只读命令也应直接放行——
        // 这正是重度用户的诉求。
        assertFalse(
            TerminalCommandSafety.requiresConfirmation("nvidia-smi", 1, listOf("nvidia-smi")),
        )
        assertTrue(
            "1 级下未命中白名单的仍要确认",
            TerminalCommandSafety.requiresConfirmation("df -h", 1, listOf("nvidia-smi")),
        )
    }

    @Test
    fun emptyPatternListTrustsNothing() {
        assertFalse(CommandAllowlist.matches("nvidia-smi", emptyList()))
    }

    @Test
    fun blankCommandDoesNotMatch() {
        assertFalse(CommandAllowlist.matches("   ", defaults))
    }

    @Test
    fun regexMetacharactersInPatternAreEscaped() {
        // 用户写 `ls [abc]` 应按字面量匹配，不该被当正则（否则 `[abc]` 会匹配 a/b/c 任意一个）。
        assertTrue(CommandAllowlist.matches("ls [abc]", listOf("ls [abc]")))
        assertFalse(CommandAllowlist.matches("ls a", listOf("ls [abc]")))
    }
}
