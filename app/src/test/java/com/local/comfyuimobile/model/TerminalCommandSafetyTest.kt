package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.54 AI 终端助手：命令安全判定与解析的单测。
 *
 * 这组测试是安全边界的锁：一旦规则被改坏（例如 `rm` 不再被识别为危险），
 * AI 提议的删除命令就会静默变成"一键执行"，后果是用户模型仓库被清空。
 */
class TerminalCommandSafetyTest {

    // ===== 危险判定 =====

    @Test
    fun flagsDestructiveCommands() {
        assertTrue(TerminalCommandSafety.isDangerous("rm -rf ~/models/loras"))
        assertTrue(TerminalCommandSafety.isDangerous("rm foo.txt"))
        assertTrue(TerminalCommandSafety.isDangerous("mv a.txt b.txt"))
        assertTrue(TerminalCommandSafety.isDangerous("pip uninstall torch"))
        assertTrue(TerminalCommandSafety.isDangerous("git reset --hard HEAD~1"))
        assertTrue(TerminalCommandSafety.isDangerous("chmod 777 /etc/passwd"))
        assertTrue(TerminalCommandSafety.isDangerous("apt install nvidia-driver"))
    }

    @Test
    fun flagsRedirectionAsDangerous() {
        // 覆盖写文件同样是不可逆操作
        assertTrue(TerminalCommandSafety.isDangerous("echo x > /etc/hosts"))
        assertTrue(TerminalCommandSafety.isDangerous("cat a >> b.txt"))
    }

    @Test
    fun readOnlyCommandsAreNotDangerous() {
        assertFalse(TerminalCommandSafety.isDangerous("ls -la ~/ComfyUI"))
        assertFalse(TerminalCommandSafety.isDangerous("du -sh ~/models"))
        assertFalse(TerminalCommandSafety.isDangerous("nvidia-smi"))
        assertFalse(TerminalCommandSafety.isDangerous("pip list"))
        assertFalse(TerminalCommandSafety.isDangerous("find ~/models -name \"*.safetensors\""))
        assertFalse(TerminalCommandSafety.isDangerous("grep -r error /tmp/comfyui.log"))
    }

    @Test
    fun findWithDeleteIsDangerousDespiteFindPrefix() {
        // find 是只读前缀，但 -delete / -exec 是写操作：不能因为前缀是 find 就放行。
        assertTrue(TerminalCommandSafety.isDangerous("find ~/models -name \"*.tmp\" -delete"))
        assertTrue(TerminalCommandSafety.isDangerous("find . -name x -exec rm {} \\;"))
        assertFalse(TerminalCommandSafety.isReadOnly("find ~/models -name \"*.tmp\" -delete"))
    }

    @Test
    fun wordBoundaryPreventsFalsePositives() {
        // "rm" 不能命中 "form" / "format"
        assertFalse(TerminalCommandSafety.isDangerous("echo format_disk_name"))
        assertFalse(TerminalCommandSafety.isDangerous("python -c \"print('confirmed')\""))
    }

    @Test
    fun pipelineWithWriteIsNotReadOnly() {
        assertFalse(TerminalCommandSafety.isReadOnly("cat a.txt | tee b.txt"))
        // 纯只读管道仍算只读
        assertTrue(TerminalCommandSafety.isReadOnly("cat a.txt | grep error"))
    }

    @Test
    fun installCommandsAreDetected() {
        assertTrue(TerminalCommandSafety.isInstall("pip install torch"))
        assertTrue(TerminalCommandSafety.isInstall("git clone https://github.com/x/y.git"))
        assertTrue(TerminalCommandSafety.isInstall("wget https://example.com/model.safetensors"))
        assertFalse(TerminalCommandSafety.isInstall("ls ~/models"))
    }

    // ===== 输出边界标记 =====

    @Test
    fun wrapsCommandWithBoundaryMarkers() {
        val wrapped = TerminalCommandSafety.wrap("ls ~", "abc123")
        assertTrue(wrapped.contains(TerminalCommandSafety.beginMarker("abc123")))
        assertTrue(wrapped.contains(TerminalCommandSafety.endMarker("abc123")))
        assertTrue(wrapped.contains("ls ~"))
    }

    @Test
    fun parsesExitCodeFromMarkerLine() {
        val token = "tok"
        assertEquals(
            0,
            TerminalCommandSafety.parseExitCode("__AI_tok_END__ rc=0", token),
        )
        assertEquals(
            2,
            TerminalCommandSafety.parseExitCode("__AI_tok_END__ rc=2", token),
        )
        assertNull(TerminalCommandSafety.parseExitCode("normal output line", token))
        // 别的 token 的结束标记不算数
        assertNull(TerminalCommandSafety.parseExitCode("__AI_other_END__ rc=0", token))
    }

    // ===== 命令解析 =====

    @Test
    fun parsesCommandsFromFencedBlock() {
        val reply = """
            先看看磁盘占用：
            ```sh
            du -sh ~/models
            ```
            然后清理临时文件：
            ```sh
            rm -rf /tmp/cache
            ```
        """.trimIndent()
        val commands = TerminalCommandSafety.parseCommands(reply)
        assertEquals(listOf("du -sh ~/models", "rm -rf /tmp/cache"), commands)
    }

    @Test
    fun doesNotTreatProseAsCommand() {
        // 普通句子不能被当成命令执行 —— 这是事故来源。
        val reply = "你可以试试看，ComfyUI 一般装在 home 目录下。"
        assertTrue(TerminalCommandSafety.parseCommands(reply).isEmpty())
    }

    @Test
    fun deduplicatesAndCapsCommands() {
        val many = (1..30).joinToString("\n") { "ls ~/dir$it" }
        val reply = "```sh\n$many\n```"
        val commands = TerminalCommandSafety.parseCommands(reply)
        assertEquals(TerminalCommandSafety.MAX_COMMANDS, commands.size)
    }

    // ===== 命令结果回喂格式 =====

    @Test
    fun commandResultFormatsForModel() {
        val result = TerminalCommandResult(
            command = "ls ~",
            output = "ComfyUI\nmodels",
            exitCode = 0,
            success = true,
        )
        val text = result.forModel()
        assertTrue(text.contains("$ ls ~"))
        assertTrue(text.contains("exit=0"))
        assertTrue(text.contains("ComfyUI"))
    }

    @Test
    fun commandResultTruncatesLongOutput() {
        val long = "x".repeat(5_000)
        val result = TerminalCommandResult("cmd", long, 0, true)
        val text = result.forModel(maxChars = 100)
        assertTrue("长输出必须被截断，否则会挤爆模型上下文", text.length < 500)
    }
}
