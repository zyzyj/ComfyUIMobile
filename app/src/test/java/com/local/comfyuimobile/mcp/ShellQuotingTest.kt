package com.local.comfyuimobile.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shell 命令引号正确性（v0.3.30）。
 *
 * ## 为什么需要这个测试
 *
 * 真机反馈：`fetch_remote` 对**任何**路径都报「云端不存在该文件」，6 次全失败。
 *
 * 根因不是 symlink（`-f` 本来就跟随软链），而是拼命令时用了**嵌套双引号**：
 *
 * ```
 * echo "SIZE=$(wc -c < "$target" 2>/dev/null || echo 0)"
 * ```
 *
 * shell 解析到第二个 `"` 就断开了引号，整条命令变成乱码，实测直接报
 * `/bin/sh: can't open "..."` —— 于是永远走 MISSING 分支。
 *
 * 这类 bug 单看代码很难发现（字符串拼接看起来“很对”），必须**真的交给 shell 跑**。
 * 所以本测试用 `sh -c` 实际执行，而不是对字符串做断言。
 */
class ShellQuotingTest {

    /** 用真实 shell 执行命令，返回 stdout（去尾部换行）。 */
    private fun sh(command: String): String {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return out.trim()
    }

    /**
     * 模拟 [ComfyMcpHost.fetchRemote] 的探测命令（修复后的写法）。
     *
     * 关键：路径用**单引号**包起来，且不出现嵌套引号。
     */
    private fun probeCommand(path: String): String {
        val q = "'" + path.replace("'", "'\\''") + "'"
        return "if [ -d $q ]; then echo DIR; " +
            "elif [ -e $q ]; then echo SIZE=$(wc -c < $q 2>/dev/null || echo 0); " +
            "echo REAL=$(readlink -f $q 2>/dev/null); " +
            "else echo MISSING; fi"
    }

    @Test
    fun probeFindsRegularFileAndReportsSize() {
        val dir = createTempDir()
        val file = java.io.File(dir, "a.txt")
        file.writeText("hello world\n")

        val out = sh(probeCommand(file.absolutePath))
        assertTrue("应报出 SIZE，实际：$out", out.contains("SIZE=12"))
        assertEquals("不该报 MISSING", false, out.contains("MISSING"))
    }

    @Test
    fun probeFollowsSymlinkToDirectory() {
        // 真机场景：/home/aistudio/data/np/ComfyUI_v2/output -> /home/aistudio/data/np/output
        val root = createTempDir()
        val real = java.io.File(root, "real").apply { mkdirs() }
        val target = java.io.File(real, "img.png").apply { writeText("PNGDATA") }
        val link = java.io.File(root, "link")
        // 用系统 ln 建软链（Java 的 createSymbolicLink 在某些 FS 上受限）
        sh("ln -sfn '${real.absolutePath}' '${link.absolutePath}'")

        val viaLink = java.io.File(link, "img.png").absolutePath
        val out = sh(probeCommand(viaLink))
        assertTrue("软链路径应能读到文件，实际：$out", out.contains("SIZE=7"))
        assertTrue("REAL 应解出真实路径，实际：$out", out.contains("REAL=${target.absolutePath}"))
    }

    @Test
    fun probeReportsDirectory() {
        val dir = createTempDir()
        val out = sh(probeCommand(dir.absolutePath))
        assertTrue("目录应报 DIR，实际：$out", out.contains("DIR"))
    }

    @Test
    fun probeReportsMissingForNonexistentPath() {
        val out = sh(probeCommand("/tmp/definitely-not-exists-$$-xyz"))
        assertTrue("不存在应报 MISSING，实际：$out", out.contains("MISSING"))
    }

    @Test
    fun probeHandlesPathWithSpaces() {
        // 文件名带空格是真实存在的（用户常给图起名带空格）。
        val dir = createTempDir()
        val file = java.io.File(dir, "my image.png")
        file.writeText("12345")

        val out = sh(probeCommand(file.absolutePath))
        assertTrue("含空格的路径应能读到，实际：$out", out.contains("SIZE=5"))
    }

    private fun createTempDir(): java.io.File =
        java.nio.file.Files.createTempDirectory("shellquote").toFile().apply { deleteOnExit() }
}
