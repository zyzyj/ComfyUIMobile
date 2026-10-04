package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * v0.2.78：「生成物 → 真实解析器」集成测试。
 *
 * 前几轮的测试都只断言 `wrap()` 生成的**字符串**长什么样（`contains(beginMarker)`
 * 之类），从未把它交给真实 shell 执行——于是两类 bug 活过了 7 个版本：
 *
 *  - 命令带行尾 `#` 注释：`#` 后的内容（含闭合 `}` 和结束标记）全被注释吃掉 →
 *    bash 报语法错误 → BEGIN/END 一个都不输出 → App 白等满 10 分钟超时
 *    （且超时不算失败 → 熔断永不触发 → 档位 2 下 5 条命令干等 50 分钟）；
 *  - 守则建议过的 `… | tail -N` 写法：管道 `$?` 取最后一段 →
 *    安装失败也报 rc=0 → 模型基于错误前提继续，熔断还不计数。
 *
 * 上面两类，**在 Kotlin 字符串层面全都正确**——只有真 shell 怎么看才算数。
 * 这组测试把 wrap 的生成物真正喂给 bash，断言「结束标记能出现、退出码是对的」。
 * 没有 bash 的环境（如 Windows 开发机）自动跳过。
 */
class WrapShellIntegrationTest {

    private val bashAvailable: Boolean by lazy {
        runCatching {
            ProcessBuilder("bash", "-c", "true").start().waitFor() == 0
        }.getOrDefault(false)
    }

    /** 把脚本交给真实 bash 执行（-c 直传，不经过任何中转转义）。 */
    private fun runInBash(script: String): String {
        val process = ProcessBuilder("bash", "-c", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(20, TimeUnit.SECONDS)
        return output
    }

    /** 生成 wrap 命令并执行；返回 (完整输出, 是否出现结束标记, 退出码)。 */
    private data class ShellResult(val output: String, val hasEndMarker: Boolean, val exitCode: Int?)

    private fun runWrapped(command: String, token: String = "t1"): ShellResult {
        val wrapped = TerminalCommandSafety.wrap(command, token)
        val output = runInBash(wrapped)
        val markerLine = output.lineSequence().firstOrNull { line ->
            TerminalCommandSafety.parseExitCode(line, token) != null
        }
        return ShellResult(
            output = output,
            hasEndMarker = markerLine != null,
            exitCode = markerLine?.let { TerminalCommandSafety.parseExitCode(it, token) },
        )
    }

    // ===== 结束标记必须出现（否则 App 挂满 10 分钟）=====

    @Test
    fun plainCommandEmitsEndMarker() {
        assumeTrue(bashAvailable)
        val result = runWrapped("echo hi")
        assertTrue("结束标记必须出现：${result.output}", result.hasEndMarker)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun trailingCommentStillEmitsEndMarker() {
        assumeTrue(bashAvailable)
        // 修复前实测：`#` 把 `}` 与 echo END 一起吃掉 → 语法错误 → 无结束标记。
        // 守则当时并未禁止这种写法，模型很爱加（pip install x  # 装依赖）。
        val result = runWrapped("echo hi  # 装依赖")
        assertTrue("带行尾注释的命令也必须出现结束标记：${result.output}", result.hasEndMarker)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun trailingSemicolonStillEmitsEndMarker() {
        assumeTrue(bashAvailable)
        // 同类残留（本轮自查发现）：尾分号拼进 `{ cmd; ; }` 是语法错误，同样挂满超时。
        val result = runWrapped("echo hi;")
        assertTrue("带尾分号的命令也必须出现结束标记：${result.output}", result.hasEndMarker)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun danglingAndOperatorStillEmitsEndMarker() {
        assumeTrue(bashAvailable)
        // 同类残留：模型把命令写成 `cmd &&` 结尾（打算下一轮接续）同样破坏包装。
        val result = runWrapped("echo hi &&")
        assertTrue("悬空 && 也必须出现结束标记：${result.output}", result.hasEndMarker)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun commentOnlyCommandCompletesAsNoop() {
        assumeTrue(bashAvailable)
        // 整条都是注释：清理后什么都不剩，用 `:`（no-op）保证"正常完成"而不是语法错误。
        val result = runWrapped("# 这一步只是说明")
        assertTrue("纯注释也必须出现结束标记：${result.output}", result.hasEndMarker)
        assertEquals(0, result.exitCode)
    }

    // ===== 引号内的 `#` 是命令的一部分，不能被清掉 =====

    @Test
    fun keepsHashInsideDoubleQuotes() {
        assumeTrue(bashAvailable)
        val result = runWrapped("echo \"a # b\"")
        assertTrue("双引号内的 # 必须保留：${result.output}", result.output.contains("a # b"))
        assertEquals(0, result.exitCode)
    }

    @Test
    fun keepsHashInsideSingleQuotes() {
        assumeTrue(bashAvailable)
        val result = runWrapped("echo 'a # b'")
        assertTrue("单引号内的 # 必须保留：${result.output}", result.output.contains("a # b"))
        assertEquals(0, result.exitCode)
    }

    @Test
    fun keepsHashInsideWord() {
        assumeTrue(bashAvailable)
        // `a#b` 不是注释（# 不在词首），bash 原样输出。
        val result = runWrapped("echo a#b")
        assertTrue("词中的 # 必须保留：${result.output}", result.output.contains("a#b"))
        assertEquals(0, result.exitCode)
    }

    // ===== 退出码必须可信（管道不得吞掉失败）=====

    @Test
    fun pipeFailureIsNotMasked() {
        assumeTrue(bashAvailable)
        // 修复前实测：false | tail -5 → rc=0（管道取最后一段）。
        // 守则当时主动建议 `… | tail -40` 这种写法 —— 失败被静默吞掉。
        val result = runWrapped("false | tail -5")
        assertTrue(result.hasEndMarker)
        assertEquals("管道前段失败必须反映到退出码", 1, result.exitCode)
    }

    @Test
    fun pipeSuccessStaysZero() {
        assumeTrue(bashAvailable)
        val result = runWrapped("echo ok | tail -1")
        assertTrue(result.hasEndMarker)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun plainFailurePropagates() {
        assumeTrue(bashAvailable)
        val result = runWrapped("false")
        assertTrue(result.hasEndMarker)
        assertEquals(1, result.exitCode)
    }

    @Test
    fun middleStageFailureIsVisible() {
        assumeTrue(bashAvailable)
        // 三段管道，中间段失败：pipefail 取首个非零。
        val result = runWrapped("echo x | false | tail -1")
        assertTrue(result.hasEndMarker)
        assertEquals(1, result.exitCode)
    }

    // ===== sanitizeCommand 的纯字符串单测（部分引号场景真 bash 也难构造）=====

    @Test
    fun sanitizeStripsUnquotedTrailingComment() {
        assertEquals("pip install x", TerminalCommandSafety.sanitizeCommand("pip install x  # 装依赖"))
    }

    @Test
    fun sanitizeKeepsQuotedHash() {
        val quoted = "echo \"a # b\""
        assertEquals(quoted, TerminalCommandSafety.sanitizeCommand(quoted))
        assertEquals("echo 'a # b'", TerminalCommandSafety.sanitizeCommand("echo 'a # b'"))
    }

    @Test
    fun sanitizeKeepsHashInsideWord() {
        assertEquals("echo a#b", TerminalCommandSafety.sanitizeCommand("echo a#b"))
    }

    @Test
    fun sanitizeNormalizesBlankToNoop() {
        assertEquals(":", TerminalCommandSafety.sanitizeCommand("# 全是注释"))
        assertEquals(":", TerminalCommandSafety.sanitizeCommand("   "))
    }

    @Test
    fun sanitizeStripsTrailingOperators() {
        assertEquals("echo hi", TerminalCommandSafety.sanitizeCommand("echo hi;"))
        assertEquals("echo hi", TerminalCommandSafety.sanitizeCommand("echo hi &&"))
        assertEquals("echo hi", TerminalCommandSafety.sanitizeCommand("echo hi |"))
        assertEquals("echo hi", TerminalCommandSafety.sanitizeCommand("echo hi ; ;"))
    }
}
