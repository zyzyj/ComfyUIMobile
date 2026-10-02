package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.64：对话上下文压缩的单测。
 *
 * 锁住的是「上下文太长怎么办」这个问题的答案：终端助手每轮都带回命令输出，
 * 几轮就把 prompt 撑爆，以前没有任何处理——只能等请求失败。
 */
class AssistantContextTest {

    private fun msg(role: TerminalMessageRole, text: String) =
        TerminalChatMessage(id = text.hashCode().toString(), role = role, text = text)

    /**
     * 造一条很长的命令输出（模拟 pip install 那类刷屏）。
     *
     * 注意单条在 prompt 里最多占 [AssistantContext] 的 1200 字上限，
     * 所以"超预算"要靠**条数**堆出来，不能靠单条造得更大。
     */
    private fun longOutput(tag: String) = msg(
        TerminalMessageRole.SYSTEM_NOTE,
        "cmd-$tag\n" + "x".repeat(9_000),
    )

    // ===== 基础 =====

    @Test
    fun emptyHistoryReturnsPromptAlone() {
        val t = AssistantContext.buildTranscript(emptyList(), "你好")
        assertEquals("你好", t.text)
        assertFalse(t.compacted)
    }

    @Test
    fun shortHistoryIsKeptVerbatim() {
        val history = listOf(
            msg(TerminalMessageRole.USER, "看看插件"),
            msg(TerminalMessageRole.ASSISTANT, "先列目录："),
            msg(TerminalMessageRole.SYSTEM_NOTE, "$ ls\ncustom_nodes"),
        )
        val t = AssistantContext.buildTranscript(history, "然后呢")
        assertFalse("短对话不该被压缩", t.compacted)
        assertTrue(t.text.contains("看看插件"))
        assertTrue(t.text.contains("然后呢"))
    }

    // ===== 压缩：先牺牲命令输出 =====

    @Test
    fun dropsOldCommandOutputsBeforeTouchingDialogue() {
        // 10 条输出（每条约 300 token，见 longOutput 注释）+ 两条用户消息
        val history = buildList {
            repeat(5) { add(longOutput("old$it")) }
            add(msg(TerminalMessageRole.USER, "继续"))
            repeat(5) { add(longOutput("new$it")) }
        }
        // 预算 900：约等于最新三条输出 + 全部对话，必须占位掉较早的输出才装得下。
        val t = AssistantContext.buildTranscript(history, "现在呢", budgetTokens = 900)
        assertTrue("超预算应触发压缩", t.compacted)
        assertTrue("应至少占位一条命令输出", t.omittedOutputs >= 1)
        // 对话本身（用户的诉求）不该被丢
        assertTrue("用户的提问要保留", t.text.contains("继续"))
        assertTrue("较新的输出要保留", t.text.contains("cmd-new4"))
        assertTrue("应留下占位提示", t.text.contains(AssistantContext.OMITTED_OUTPUT))
    }

    @Test
    fun compactionKeepsResultWithinBudget() {
        val history = (1..12).map { longOutput("n$it") }
        val t = AssistantContext.buildTranscript(history, "继续", budgetTokens = 900)
        assertTrue("确实压缩了", t.compacted)
        assertTrue("压缩后必须回到预算内", t.tokens <= 1_200)
    }

    // ===== 压缩：不够才动对话 =====

    @Test
    fun dropsOldestTurnsWhenStillOverBudgetAfterOutputCompaction() {
        // 全是用户消息（没有可压缩的输出），只能丢较早的整轮
        val history = (1..40).map { msg(TerminalMessageRole.USER, "提问$it " + "y".repeat(500)) }
        val t = AssistantContext.buildTranscript(history, "最新一条", budgetTokens = 800)
        assertTrue("应丢弃较早的对话", t.omittedTurns > 0)
        assertTrue("应留下省略提示", t.text.contains(AssistantContext.OMITTED_TURNS_PREFIX))
        assertTrue("最新的提问必须在", t.text.contains("最新一条"))
        assertTrue("压缩后仍要回到预算内", t.tokens <= AssistantContext.DEFAULT_TOKEN_BUDGET)
    }

    @Test
    fun alwaysKeepsAtLeastRecentMessages() {
        // 极端情况：单条就远超预算，也不能把历史清空——模型至少该看到最近发生了什么
        val history = listOf(
            msg(TerminalMessageRole.USER, "z".repeat(50_000)),
            msg(TerminalMessageRole.USER, "w".repeat(50_000)),
        )
        val t = AssistantContext.buildTranscript(history, "收尾")
        assertTrue(t.text.contains("收尾"))
        assertTrue("最后的提问不能丢", t.text.contains("w".repeat(100)))
    }

    @Test
    fun respectsCustomBudget() {
        val history = listOf(longOutput("a"), longOutput("b"))
        val tight = AssistantContext.buildTranscript(history, "x", budgetTokens = 100)
        assertTrue(tight.compacted)
        val roomy = AssistantContext.buildTranscript(history, "x", budgetTokens = 100_000)
        assertFalse(roomy.compacted)
    }

    // ===== token 估算 =====

    @Test
    fun estimatesChineseAsOneTokenPerChar() {
        // 中文按 1 字 1 token 估：量级对就行，预算本身留了余量
        assertEquals(4, AssistantContext.estimateTokens("你好世界"))
    }

    @Test
    fun estimatesLatinRoughlyFourCharsPerToken() {
        assertEquals(1, AssistantContext.estimateTokens("abcd"))
        assertEquals(2, AssistantContext.estimateTokens("abcdefgh"))
        assertEquals(0, AssistantContext.estimateTokens(""))
    }
}
