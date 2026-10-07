package com.local.comfyuimobile.mcp

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长任务判定单测（v0.2.99，计划书 2.1 ④）。
 *
 * 这层的风险是**误提**：对着一条 `ls` 说教只会让返回变噪，而 AI 对噪话免疫力很高。
 * 所以用例一半是"该提的提"，一半是"不该提的必须闭嘴"。
 *
 * 断言先用 Python 复刻同一套正则跑过 18 个用例、确认无误后才落成。
 */
class TerminalTaskHeuristicsTest {

    private fun hint(cmd: String, terminal: String = "default") =
        TerminalTaskHeuristics.longTaskHint(cmd, terminal)

    // ===== 该提醒：明显的长任务 =====

    @Test
    fun hintsForDownloadsAndInstalls() {
        assertNotNull(hint("pip install -r requirements.txt"))
        assertNotNull(hint("wget https://example.com/y.safetensors"))
        assertNotNull(hint("curl -O https://example.com/y.bin"))
        assertNotNull(hint("git clone https://github.com/a/b"))
        assertNotNull(hint("git pull"))
    }

    @Test
    fun hintsForStartupScripts() {
        assertNotNull(hint("bash _st.sh"))
        assertNotNull(hint("bash start.sh"))
        assertNotNull(hint("sh setup.sh"))
    }

    @Test
    fun hintsForArchivesAndTraining() {
        assertNotNull(hint("tar -xzf a.tar.gz"))
        assertNotNull(hint("unzip x.zip"))
        assertNotNull(hint("python sync_loras.py"))
        assertNotNull(hint("pytest"))
    }

    // ===== 不该提醒 =====

    @Test
    fun staysQuietForQuickCommands() {
        assertNull(hint("ls -la"))
        assertNull(hint("nvidia-smi"))
        assertNull(hint("cat foo.txt"))
        assertNull(hint("echo hello"))
        assertNull(hint(""))
    }

    @Test
    fun staysQuietWhenAlreadyBackgrounded() {
        // 已经放后台/不等待：说明调用方已在处理"不等它"，不必再说。
        assertNull(hint("pip install -r r.txt > log 2>&1 &"))
        assertNull(hint("nohup bash start.sh > /dev/null 2>&1 &"))
        assertNull(hint("python train.py | tee log.txt"))
    }

    @Test
    fun staysQuietWhenAlreadyUsingDedicatedTerminal() {
        // 已经在用独立终端 = 已经在分工，不要重复说教。
        assertNull(hint("pip install x", terminal = "work"))
        assertNull(hint("bash _st.sh", terminal = "comfy"))
    }

    @Test
    fun hintTellsWhichTerminalAndWhy() {
        val text = hint("wget https://example.com/big.safetensors")
        assertTrue(text!!, text.contains("work"))
        // 要说清后果，否则等于没说（AI 不知道"混在一起"具体坏在哪）。
        assertTrue(text, text.contains("输出") || text.contains("刷"))
    }
}
