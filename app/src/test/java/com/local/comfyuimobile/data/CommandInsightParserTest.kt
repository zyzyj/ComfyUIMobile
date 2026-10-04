package com.local.comfyuimobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.82：命令输出解析器的单测。
 *
 * 这组测试锁的是**格式契约**——`nvidia-smi` / `df` 的输出随驱动与系统版本变化，
 * 解析错数字比不解析更糟（用户会当真）。所以每条命令都覆盖：
 * 正常输出 / 多 GPU / 格式不符时返回 null（退回原文）。
 */
class CommandInsightParserTest {

    private val nvidiaReal = """
        Mon Oct  5 09:12:33 2026
        +-----------------------------------------------------------------------------+
        | NVIDIA-SMI 535.104.05             Driver Version: 535.104.05   CUDA Version: 12.2     |
        |-------------------------------+----------------------+----------------------+
        | GPU  Name        Persistence-M| Bus-Id        Disp.A | Volatile Uncorr. ECC |
        | Fan  Temp  Perf  Pwr:Usage/Cap|         Memory-Usage | GPU-Util  Compute M. |
        |                               |                      |               MIG M. |
        |===============================+======================+======================|
        |   0  Tesla V100-SXM2-32GB    Off  | 00000000:00:04.0 Off |                    0 |
        | N/A   38C    P0    38W / 300W |   1234MiB / 32768MiB |      0%      Default |
        |                               |                      |                  N/A |
        +-------------------------------+----------------------+----------------------+

        +-----------------------------------------------------------------------------+
        | Processes:                                                                  |
        |  GPU   GI   CI        PID   Type   Process name                  GPU Memory |
        |        ID   ID                                                   Usage      |
        |=============================================================================|
        |    0   N/A  N/A      1234      C   python                             1200MiB |
        +-----------------------------------------------------------------------------+
    """.trimIndent()

    @Test
    fun parsesNvidiaSmiRealTable() {
        val card = CommandInsightParser.parse("nvidia-smi", nvidiaReal)
        assertNotNull("真实 V100 表格应能解析", card)
        card!!
        assertTrue("标题应含型号", card.title.contains("Tesla V100-SXM2-32GB"))
        assertEquals(1, card.bars.size)
        val bar = card.bars.first()
        assertEquals("显存", bar.label)
        assertEquals(32768.0, bar.total, 0.1)
        assertEquals(1234.0, bar.used, 0.1)
        assertEquals(0.0377, bar.ratio, 0.001)
        assertTrue("展示值应换算成 GB", bar.display.contains("32"))
        // 指标
        val metrics = card.metrics.associate { it.label to it.value }
        assertEquals("535.104.05", metrics["驱动"])
        assertEquals("12.2", metrics["CUDA"])
        assertEquals("0%", metrics["利用率"])
        assertEquals("38°C", metrics["温度"])
        assertEquals("38 / 300 W", metrics["功耗"])
        // 进程
        assertEquals(1, card.rows.size)
        assertTrue(card.rows.first().primary.contains("python"))
        assertTrue(card.rows.first().secondary.contains("1234"))
    }

    @Test
    fun nvidiaSmiFailedCommandReturnsNull() {
        assertNull(CommandInsightParser.parse("nvidia-smi", "nvidia-smi: command not found"))
        assertNull(CommandInsightParser.parse("nvidia-smi", ""))
    }

    @Test
    fun nvidiaSmiMultipleGpus() {
        val multi = """
            | NVIDIA-SMI 535.104.05    Driver Version: 535.104.05  CUDA Version: 12.2 |
            |   0  Tesla V100-SXM2-32GB    Off  | 00000000:00:04.0 Off |   0 |
            | N/A   38C    P0    38W / 300W |   100MiB / 32768MiB |      0%   Default |
            |   1  Tesla V100-SXM2-32GB    Off  | 00000000:00:05.0 Off |   0 |
            | N/A   40C    P0    40W / 300W |   200MiB / 32768MiB |      5%   Default |
        """.trimIndent()
        val card = CommandInsightParser.parse("nvidia-smi", multi)
        assertNotNull(card)
        assertEquals(2, card!!.bars.size)
        assertTrue(card.title.contains("2 张卡"))
    }

    @Test
    fun parsesDfHumanReadable() {
        val df = """
            Filesystem      Size  Used Avail Use% Mounted on
            overlay          99G   42G   57G  43% /
            tmpfs            64M     0   64M   0% /dev
            /dev/sdb       1000G  100G  900G  10% /home/user
        """.trimIndent()
        val card = CommandInsightParser.parse("df -h ~", df)
        assertNotNull(card)
        card!!
        assertEquals("磁盘用量", card.title)
        assertEquals("overlay / tmpfs / /dev/sdb 三行都可解析", 3, card.bars.size)
        val root = card.bars.first()
        assertEquals("/", root.label)
        assertEquals(43, (root.ratio * 100).toInt())
        assertTrue(root.display.contains("42G"))
    }

    @Test
    fun parsesFree() {
        val free = """
                          total        used        free      shared  buff/cache   available
            Mem:          125Gi        30Gi        80Gi       1.0Gi        15Gi        90Gi
        """.trimIndent()
        val card = CommandInsightParser.parse("free -h", free)
        assertNotNull(card)
        assertEquals(1, card!!.bars.size)
        assertTrue(card.bars.first().display.contains("125"))
    }

    @Test
    fun parsesPsAuxGrepAndSkipsGrepItself() {
        val ps = """
            USER       PID %CPU %MEM    VSZ   RSS TTY      STAT START   TIME COMMAND
            root      1234  3.5  1.2 123456 78901 ?        Sl   09:00   0:10 python main.py --listen
            root      5678  0.0  0.0  12345  6789 ?        S    09:01   0:00 grep -i comfy
        """.trimIndent()
        val card = CommandInsightParser.parse("ps aux | grep -i \"[c]omfy\"", ps)
        assertNotNull(card)
        assertEquals("只应保留真进程，过滤 grep 自身", 1, card!!.rows.size)
        assertTrue(card.rows.first().primary.contains("python"))
    }

    @Test
    fun unrelatedCommandReturnsNull() {
        assertNull(CommandInsightParser.parse("ls -la", "total 4\ndrwxr-xr-x 2 root root"))
        assertNull(CommandInsightParser.parse("pip list", "Package  Version\nnumpy   1.0"))
    }

    @Test
    fun dfWithUnparsableSizeReturnsNull() {
        assertNull(CommandInsightParser.parse("df -h", "Filesystem  Size  Used Avail Use% Mounted on"))
    }
}
