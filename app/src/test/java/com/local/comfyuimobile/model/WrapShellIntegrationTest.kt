package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 「生成物 → 真实解析器」集成测试（v0.2.78 建立，v0.2.79 扩展到 POSIX shell）。
 *
 * 前几轮的测试都只断言 `wrap()` 生成的**字符串**长什么样（`contains(beginMarker)`
 * 之类），从未把它交给真实 shell 执行——于是两类 bug 活过了 7 个版本：
 *
 *  - 命令带行尾 `#` 注释：`#` 后的内容（含闭合 `}` 和结束标记）全被注释吃掉 →
 *    shell 报语法错误 → BEGIN/END 一个都不输出 → App 白等满 10 分钟超时
 *    （且超时不算失败 → 熔断永不触发 → 档位 2 下 5 条命令干等 50 分钟）；
 *  - 守则建议过的 `… | tail -N` 写法：管道 `$?` 取最后一段 →
 *    安装失败也报 rc=0 → 模型基于错误前提继续，熔断还不计数。
 *
 * v0.2.79 补上同类第三案：v0.2.78 直接 `set -o pipefail`，而 **dash < 0.5.13**
 * 不支持该选项。`set` 是 POSIX 特殊内建，失败时中止整条命令、`|| true` 挡不住 →
 * 所有命令（不只管道命令）挂满 10 分钟。当时只测 bash 所以没暴露。
 * 现在 wrap 改为「先探测再设置」，并且**两种 shell 都要测**：
 *  - bash：功能验证（pipefail 生效、141 归一化）；
 *  - `/bin/sh`（CI 的 Ubuntu 上就是 dash）：兼容性验证（不支持时也不会挂）。
 *
 * 这层测试的核心断言：**结束标记必须出现**（否则 App 白等 10 分钟）、
 * **退出码必须准确**。没有对应 shell 的环境自动跳过。
 */
class WrapShellIntegrationTest {

    /** 可用的 shell（命令名 → 是否可用）。 */
    private fun shellAvailable(shell: String): Boolean = runCatching {
        ProcessBuilder(shell, "-c", "true").start().waitFor() == 0
    }.getOrDefault(false)

    private val bashAvailable: Boolean by lazy { shellAvailable("bash") }
    private val shAvailable: Boolean by lazy { shellAvailable("/bin/sh") }

    /** 把脚本交给指定 shell 执行（-c 直传，不经过任何中转转义）。 */
    private fun runInShell(shell: String, script: String): String {
        val process = ProcessBuilder(shell, "-c", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(20, TimeUnit.SECONDS)
        return output
    }

    /** 生成 wrap 命令并执行；返回 (完整输出, 是否出现结束标记, 退出码)。 */
    private data class ShellResult(val output: String, val hasEndMarker: Boolean, val exitCode: Int?)

    private fun runWrapped(command: String, shell: String = "bash", token: String = "t1"): ShellResult {
        val wrapped = TerminalCommandSafety.wrap(command, token)
        val output = runInShell(shell, wrapped)
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
        // 同类残留（自查发现）：尾分号拼进 `{ cmd; ; }` 是语法错误，同样挂满超时。
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

    // ===== 引号语义：引号内的 `#` 是命令的一部分，不能被清掉 =====

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

    // ===== 不配对引号：必须立即报错（哨兵），不能让 shell 挂住 =====

    @Test
    fun unbalancedQuoteFailsFastInsteadOfHanging() {
        assumeTrue(bashAvailable)
        // 修复前实测：`echo "abc` 这类命令在交互式 shell 会进入续行等待 → 没有结束标记。
        // 修复后：返回一条立即报错的哨兵命令（rc=1，提示引号不配对）。
        val result = runWrapped("echo \"abc")
        assertTrue("不配对引号也要有结束标记（不能挂住）：${result.output}", result.hasEndMarker)
        assertEquals("哨兵命令应报失败", 1, result.exitCode)
        assertTrue("要告诉用户原因：${result.output}", result.output.contains("引号不配对"))
    }

    @Test
    fun trailingBackslashFailsFastInsteadOfHanging() {
        assumeTrue(bashAvailable)
        // 行尾反斜杠同样让交互式 shell 等待续行。
        val result = runWrapped("echo a\\")
        assertTrue("行尾反斜杠也要有结束标记：${result.output}", result.hasEndMarker)
        assertEquals(1, result.exitCode)
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

    // ===== /bin/sh（POSIX）兼容性：v0.2.79 回归的锁 =====
    //
    // CI 的 ubuntu-latest 上 /bin/sh 是 dash。v0.2.78 直接 `set -o pipefail`，
    // dash < 0.5.13 下特殊内建失败会中止整条命令（`|| true` 挡不住）——
    // **所有命令**没有结束标记。这一节就是那条回归的保险。

    @Test
    fun shPlainCommandEmitsEndMarker() {
        assumeTrue(shAvailable)
        val result = runWrapped("echo hi", shell = "/bin/sh")
        assertTrue("/bin/sh 下结束标记必须出现（否则整条命令挂满）：${result.output}", result.hasEndMarker)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun shFailurePropagates() {
        assumeTrue(shAvailable)
        val result = runWrapped("false", shell = "/bin/sh")
        assertTrue(result.hasEndMarker)
        assertEquals(1, result.exitCode)
    }

    @Test
    fun shCommentCommandEmitsEndMarker() {
        assumeTrue(shAvailable)
        val result = runWrapped("echo hi  # 装依赖", shell = "/bin/sh")
        assertTrue(result.hasEndMarker)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun shPipeStillEmitsEndMarker() {
        assumeTrue(shAvailable)
        // dash 不支持 pipefail 时退化为旧语义（rc 取最后一段）——不理想但可用；
        // 关键是**有结束标记**，不会白等 10 分钟。
        val result = runWrapped("echo ok | tail -1", shell = "/bin/sh")
        assertTrue("/bin/sh 下管道命令必须有结束标记：${result.output}", result.hasEndMarker)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun shUnbalancedQuoteStillFailsFast() {
        assumeTrue(shAvailable)
        val result = runWrapped("echo \"abc", shell = "/bin/sh")
        assertTrue(result.hasEndMarker)
        assertEquals(1, result.exitCode)
    }

    // ===== sanitizeCommand 的纯字符串单测（部分引号场景真 shell 也难构造）=====

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
