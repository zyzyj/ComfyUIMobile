package com.local.comfyuimobile.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动等待文案单测（v0.3.8，P0-1）。
 *
 * 真机现象：选了 GPU 启动，等半天，刷新还是失败，最后只得到一句
 * 「状态未变化，可点「刷新」查看」——没说等了多久、没说原因、没说下一步。
 *
 * 而代码注释里早就记下了实测（cf08：平台全程没受理 = 配额见底），
 * **知识留在注释里，没进界面**。
 */
class AiStudioStartupHintTest {

    @Test
    fun allocatingMessageStatesTheCeiling() {
        // 用户必须知道\"最长等多久\"，否则只能干等或反复重点启动（每次都可能再扣卡）。
        val text = AiStudioSchedules.allocatingMessage(waitedSeconds = 48)
        assertTrue("应含已等秒数：$text", text.contains("48"))
        assertTrue("应含上限分钟数：$text", text.contains("5 分钟"))
    }

    @Test
    fun timeoutWhenPlatformNeverAcceptedGivesReasonAndNextSteps() {
        // 从未受理 = 配额见底那一类。必须给出原因与可行动作，
        // 而不是「状态未变化」。
        val text = AiStudioSchedules.startupTimeoutMessage(sawRunning = false, waitedSeconds = 300)
        assertTrue("应说清等了多久：$text", text.contains("5 分钟"))
        assertTrue("应提到余额：$text", text.contains("余额"))
        assertTrue("应提到换档位：$text", text.contains("档位"))
        assertTrue("应提到换账号：$text", text.contains("账号"))
        assertTrue("不该只说状态未变化：$text", !text.contains("状态未变化"))
    }

    @Test
    fun timeoutAfterAcceptedSaysPlatformFailedToAllocate() {
        // 受理过又退回：那是平台没能分配机器，与\"配额见底\"不是一回事。
        val text = AiStudioSchedules.startupTimeoutMessage(sawRunning = true, waitedSeconds = 300)
        assertTrue("应说平台没分配：$text", text.contains("没能分配到机器"))
        assertTrue("应给重试/换档建议：$text", text.contains("档位"))
        assertTrue("不该提余额不足（会误导）：$text", !text.contains("余额"))
    }

    @Test
    fun twoCasesAreDistinct() {
        // 两种文案必须不同——用户的下一步完全不同。
        val never = AiStudioSchedules.startupTimeoutMessage(false, 300)
        val after = AiStudioSchedules.startupTimeoutMessage(true, 300)
        assertTrue(never != after)
    }

    @Test
    fun waitCeilingMatchesPollingLoop() {
        // 轮询是前 5 轮 4 秒 + 后 35 轮 8 秒 = 300 秒，文案里的 5 分钟必须与它一致，
        // 否则用户会以为还能等更久。
        assertEquals(300, AiStudioSchedules.MAX_STARTUP_WAIT_SECONDS)
    }
}
