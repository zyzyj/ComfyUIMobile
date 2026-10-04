package com.local.comfyuimobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 指标（v0.2.83：只留手机玩家看的三个——驱动/CUDA/功耗已不再显示）
        val metrics = card.metrics.associate { it.label to it.value }
        assertEquals("0%", metrics["利用率"])
        assertEquals("38°C", metrics["温度"])
        assertFalse("驱动版本不再占位", metrics.containsKey("驱动"))
        assertFalse("CUDA 版本不再占位", metrics.containsKey("CUDA"))
        assertFalse("功耗不再占位", metrics.containsKey("功耗"))
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
        // v0.2.84：进度用 df 自己的 Use% 列（43%），而不是 used/size。
        assertEquals(0.43, root.ratio, 0.001)
        assertEquals("剩余 57G", root.display)
        assertTrue("副行仍带上总量与 Use%", root.secondary.contains("99G"))
        assertTrue("副行带上 Use% 列 43%", root.secondary.contains("43%"))
    }

    @Test
    fun dfUsesAvailColumnNotSizeMinusUsed() {
        // v0.2.84（真 bug）：ext4 给 root 留 5% 保留块，于是 Used + Avail ≠ Size。
        // 以前用 size - used 算"剩余"，实测偏差可达 50 倍（`/` 真剩 0.1G，减出来 5.0G）
        // ——而这个数字是守则要求 AI 拿去对照下载体积的，错了就直接导致判断错。
        val df = """
            Filesystem      Size  Used Avail Use% Mounted on
            /dev/sda1       100G   96G  0.5G  96% /
        """.trimIndent()
        val card = CommandInsightParser.parse("df -h", df)
        assertNotNull(card)
        val bar = card!!.bars.first()
        // 必须是 Avail 列的 0.5G，不是 100-96=4G
        assertEquals("剩余 0.5G", bar.display)
        assertTrue("副行里也该是真实的已用 96G", bar.secondary.contains("96G"))
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
        // v0.2.84：`ls -l` 已有卡片，改用真正的无关命令；
        // 纯 `ls`（无 -l）仍不做卡片（它本来就是简洁名单）。
        assertNull(CommandInsightParser.parse("pip list", "Package  Version\nnumpy   1.0"))
        assertNull(CommandInsightParser.parse("ls ~", "ComfyUI  models  work"))
    }

    // ===== ls -l（v0.2.84）=====

    @Test
    fun parsesLsLongIntoNameList() {
        val ls = """
            total 63
            drwxr-xr-x  7 aistudio aistudio 4096 Aug  3 10:00 ComfyUI-Manager
            drwxr-xr-x  9 aistudio aistudio 4096 Aug  3 10:00 ComfyUI-Easy-Use
            drwxr-xr-x 12 aistudio aistudio 4096 Aug  3 10:00 ComfyUI-Impact-Pack
            -rw-r--r--  1 aistudio aistudio  123 Aug  3 10:00 config.json
        """.trimIndent()
        val card = CommandInsightParser.parse("ls -l ~/ComfyUI/custom_nodes", ls)
        assertNotNull(card)
        card!!
        assertEquals("共 4 项（3 目录 / 1 文件）", card.title)
        assertEquals(4, card.rows.size)
        // 目录名后加 /，文件不加——一眼区分，不用额外列。
        assertTrue(card.rows.any { it.primary == "ComfyUI-Manager/" })
        assertTrue(card.rows.any { it.primary == "config.json" })
        // 权限/属主/日期不进卡片（它们是噪音）。
        assertTrue(card.rows.none { it.primary.contains("drwx") })
    }

    @Test
    fun lsLongWithSpacedFilenameKeepsWholeName() {
        val ls = """
            total 8
            drwxr-xr-x 2 aistudio aistudio 4096 Aug  3 10:00 我的 模型
        """.trimIndent()
        val card = CommandInsightParser.parse("ls -l", ls)
        assertNotNull(card)
        assertEquals("我的 模型/", card!!.rows.first().primary)
    }

    // ===== 共享 GPU 说明（v0.2.84）=====

    @Test
    fun explainsSharedGpuWhenMemoryFreeButUtilized() {
        // 显存 0 但整卡利用率 50%——数字看着矛盾，卡片要解释一句。
        val smi = """
            | NVIDIA-SMI 535.104.05    Driver Version: 535.104.05  CUDA Version: 12.2 |
            |   0  Tesla V100-SXM2-16GB    Off  | 00000000:00:04.0 Off |   0 |
            | N/A   60C    P0    72W / 300W |      0MiB / 16384MiB |     50%   Default |
        """.trimIndent()
        val card = CommandInsightParser.parse("nvidia-smi", smi)
        assertNotNull(card)
        assertTrue("应给出共享 GPU 的说明", card!!.note.contains("整卡利用率"))
    }

    @Test
    fun noSharedGpuNoteWhenMemoryIsUsedByUs() {
        val smi = """
            | NVIDIA-SMI 535.104.05    Driver Version: 535.104.05  CUDA Version: 12.2 |
            |   0  Tesla V100-SXM2-16GB    Off  | 00000000:00:04.0 Off |   0 |
            | N/A   60C    P0    72W / 300W |   8000MiB / 16384MiB |     50%   Default |
        """.trimIndent()
        val card = CommandInsightParser.parse("nvidia-smi", smi)
        assertNotNull(card)
        assertTrue("自己在用显存时不该加这句", card!!.note.isBlank())
    }

    @Test
    fun dfWithUnparsableSizeReturnsNull() {
        assertNull(CommandInsightParser.parse("df -h", "Filesystem  Size  Used Avail Use% Mounted on"))
    }
}
