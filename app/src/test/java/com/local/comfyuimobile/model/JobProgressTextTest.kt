package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度文案单测（v0.2.90）。
 *
 * 界面卡片与后台通知共用这些函数——锁住\"百分比是辅助、节点名与已用时间为主\"，
 * 免得以后有人只改一处文案又对不上。
 */
class JobProgressTextTest {

    @Test
    fun showsElapsedEvenWithoutPercent() {
        // 反代下百分比经常丢失——已用时间必须仍能给出进度感。
        val text = JobProgressText.title("KSampler", 83_000L, percent = null)
        assertTrue(text.contains("已用 1:23"))
        assertTrue(text.startsWith("正在生成"))
    }

    @Test
    fun appendsPercentWhenAvailable() {
        assertEquals("正在生成 47% · 已用 1:23", JobProgressText.title("KSampler", 83_000L, 47))
    }

    @Test
    fun ignoresOutOfRangePercent() {
        // 越界/无效百分比不能显示成 "-1%" 或 "300%"。
        val text = JobProgressText.title(null, 1_000L, -1)
        assertTrue(!text.contains("%"))
    }

    @Test
    fun pendingSaysQueued() {
        assertTrue(JobProgressText.title(null, 5_000L, null, pending = true).startsWith("排队中"))
    }

    @Test
    fun subtitlePrefersNode() {
        assertEquals("KSampler", JobProgressText.subtitle("KSampler", "wf.json"))
        // 没节点名时退到工作流名，别显示空白。
        assertEquals("wf.json", JobProgressText.subtitle("  ", "wf.json"))
        assertEquals("等待服务器推进…", JobProgressText.subtitle(null, null))
    }

    @Test
    fun formatsDuration() {
        assertEquals("0:05", JobProgressText.formatDuration(5_000))
        assertEquals("1:23", JobProgressText.formatDuration(83_000))
        assertEquals("1:02:03", JobProgressText.formatDuration(3_723_000))
        // 负数不能出现（时钟异常时也不能显示 -0:01）。
        assertEquals("0:00", JobProgressText.formatDuration(-500))
    }
}
