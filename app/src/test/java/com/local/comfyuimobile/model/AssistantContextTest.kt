package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.64 起：对话上下文压缩的单测。
 *
 * 锁住的是「上下文太长怎么办」这个问题的答案：终端助手每轮都带回命令输出，
 * 几轮就把 prompt 撑爆，以前没有任何处理——只能等请求失败。
 *
 * v0.2.83 适配：命令输出不再是独立消息（`TERMINAL_OUTPUT` 角色已删），改为挂在
 * 消息的 [ToolCall] 上。压缩与去重逻辑随之改成对 toolCall.output 生效。
 */
class AssistantContextTest {

    private fun msg(role: TerminalMessageRole, text: String) =
        TerminalChatMessage(id = text.hashCode().toString(), role = role, text = text)

    /** 造一条带长输出的工具调用消息（模拟 pip install 那类刷屏）。 */
    private fun longOutput(tag: String) = msg(
        TerminalMessageRole.ASSISTANT,
        "命令输出如下",
    ).copy(
        toolCalls = listOf(
            ToolCall(
                id = tag,
                command = "cmd-$tag",
                status = ToolCallStatus.OK,
                riskTier = RiskTier.READ_ONLY,
                exitCode = 0,
                output = "cmd-$tag\n" + "x".repeat(9_000),
            ),
        ),
    )

    /** 造一条带短输出的工具调用消息。 */
    private fun outputMessage(tag: String, body: String, id: String = tag) = msg(
        TerminalMessageRole.ASSISTANT,
        "输出：",
    ).copy(
        toolCalls = listOf(
            ToolCall(
                id = id,
                command = "cmd-$tag",
                status = ToolCallStatus.OK,
                riskTier = RiskTier.READ_ONLY,
                exitCode = 0,
                output = body,
            ),
        ),
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
            msg(TerminalMessageRole.SYSTEM_NOTE, "已排队 1 条"),
        )
        val t = AssistantContext.buildTranscript(history, "然后呢")
        assertFalse("短对话不该被压缩", t.compacted)
        assertTrue(t.text.contains("看看插件"))
        assertTrue(t.text.contains("然后呢"))
    }

    // ===== 压缩：先牺牲命令输出 =====

    @Test
    fun dropsOldCommandOutputsBeforeTouchingDialogue() {
        val history = buildList {
            repeat(5) { add(longOutput("old$it")) }
            add(msg(TerminalMessageRole.USER, "继续"))
            repeat(5) { add(longOutput("new$it")) }
        }
        val t = AssistantContext.buildTranscript(history, "现在呢", budgetTokens = 900)
        assertTrue("超预算应触发压缩", t.compacted)
        assertTrue("应至少占位一条命令输出", t.omittedOutputs >= 1)
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

    // ===== 本轮提问不能重复入参（v0.2.76）=====

    @Test
    fun historyForPromptDropsTrailingDuplicateQuestion() {
        val q = "看下显存"
        val messages = listOf(msg(TerminalMessageRole.USER, q))
        val history = AssistantContext.historyForPrompt(messages, q)
        assertTrue("末尾重复的提问要被去掉", history.isEmpty())
    }

    @Test
    fun firstTurnQuestionAppearsOnceInPrompt() {
        val q = "看下显存"
        val messages = listOf(msg(TerminalMessageRole.USER, q))
        val history = AssistantContext.historyForPrompt(messages, q)
        val t = AssistantContext.buildTranscript(history, q)
        assertEquals(
            "本轮提问在 prompt 里只能出现一次",
            1,
            Regex(Regex.escape(q)).findAll(t.text).count(),
        )
    }

    @Test
    fun historyForPromptKeepsDifferentText() {
        val mixed = listOf(
            msg(TerminalMessageRole.USER, "看下显存"),
            msg(TerminalMessageRole.ASSISTANT, "好的"),
        )
        assertEquals(2, AssistantContext.historyForPrompt(mixed, "装个插件").size)
    }

    @Test
    fun historyForPromptKeepsEarlierSameTextQuestion() {
        val dup = listOf(
            msg(TerminalMessageRole.USER, "看下显存"),
            msg(TerminalMessageRole.ASSISTANT, "好的"),
            msg(TerminalMessageRole.USER, "看下显存"),
        )
        assertEquals(2, AssistantContext.historyForPrompt(dup, "看下显存").size)
    }

    // ===== 角色语义（v0.2.75 的教训，v0.2.83 后更简单）=====
    //
    // v0.2.75 的 bug：执行标记复用了 USER 角色，导致 `indexOfLast { role == USER }`
    // 会命中「（已执行）xxx」，重试发出去的是那句话而不是用户的真实问题。
    // v0.2.83 直接取消了执行标记消息（状态内联在 ToolCall 上），这类"角色语义污染"
    // 从根上不可能再发生。这里锁住：执行过的命令**不会**产生额外的 USER 消息。

    @Test
    fun executedCommandDoesNotProduceExtraUserMessage() {
        val messages = listOf(
            msg(TerminalMessageRole.USER, "帮我看看显存"),
            outputMessage("nvidia-smi", "V100 32G"),
        )
        val lastUserIndex = messages.indexOfLast { it.role == TerminalMessageRole.USER }
        assertEquals("USER 只能是最初那句提问", 0, lastUserIndex)
        assertEquals("帮我看看显存", messages[lastUserIndex].text)
    }

    // ===== 跟进轮的历史准备（v0.2.74 / v0.2.83）=====

    @Test
    fun followUpHistoryDropsTrailingToolCall() {
        // 跟进轮会把命令结果作为 followUp 单独传入，而那条结果同时也在 toolCall 上。
        // 不摘掉的话同一份结果会进 prompt 两次，且第二次标签是 `User:`——
        // 与"让模型分清机器输出与用户的话"直接冲突。
        val messages = listOf(
            msg(TerminalMessageRole.USER, "看下插件"),
            outputMessage("ls", "\$ ls\ncustom_nodes"),
        )
        val history = AssistantContext.historyForFollowUp(messages)
        assertEquals("消息条数不变（只摘 toolCall，不删消息）", 2, history.size)
        assertTrue(
            "尾部那个工具调用要被整条摘掉",
            history.last().toolCalls.isEmpty(),
        )
    }

    @Test
    fun followUpHistoryKeepsEarlierToolCalls() {
        val messages = listOf(
            outputMessage("nvidia-smi", "\$ nvidia-smi\nV100", id = "call-1"),
            msg(TerminalMessageRole.ASSISTANT, "显存 32G，够用"),
            outputMessage("pip", "\$ pip install x\n成功", id = "call-2"),
        )
        val history = AssistantContext.historyForFollowUp(messages)
        assertEquals("消息条数不变", 3, history.size)
        assertTrue("较早的工具调用必须保留", history[0].toolCalls.isNotEmpty())
        assertTrue("尾部那条已被摘掉", history[2].toolCalls.isEmpty())
    }

    @Test
    fun followUpHistoryIsUnchangedWhenNoToolOutput() {
        val messages = listOf(
            msg(TerminalMessageRole.USER, "你好"),
            msg(TerminalMessageRole.ASSISTANT, "在的"),
        )
        assertEquals(messages, AssistantContext.historyForFollowUp(messages))
    }

    // ===== 角色标记（v0.2.72 / v0.2.83）=====

    @Test
    fun terminalOutputGetsItsOwnSpeakerNotSystem() {
        // 命令输出在 prompt 里有**自己的**标记，不能与「已排队 N 条」这类旁注
        // 混为同一个 speaker——守则承诺的「终端输出标记」是防间接注入的关键。
        val history = listOf(
            msg(TerminalMessageRole.USER, "看下插件"),
            outputMessage("ls", "$ ls\ncustom_nodes"),
            msg(TerminalMessageRole.SYSTEM_NOTE, "已排队 1 条"),
        )
        val t = AssistantContext.buildTranscript(history, "然后呢")
        assertTrue("命令输出要有自己的标记", t.text.contains("${AssistantContext.TERMINAL_OUTPUT_SPEAKER}:"))
        assertTrue("旁注仍是 System", t.text.contains("System: 已排队 1 条"))
        // 关键：命令输出不能是 "System:"，否则与旁注混淆
        assertFalse(
            "命令输出不能与旁注混为同一个 speaker",
            t.text.contains("System: $ ls"),
        )
    }

    @Test
    fun speakerNameMatchesPlaybookWording() {
        assertEquals("终端输出", AssistantContext.TERMINAL_OUTPUT_SPEAKER)
        val playbook = com.local.comfyuimobile.network.TerminalPlaybook.RESULT_NOTICE
        assertTrue(
            "守则里必须出现同一个标记",
            playbook.contains("${AssistantContext.TERMINAL_OUTPUT_SPEAKER}:"),
        )
    }

    @Test
    fun noMatchOutputIsLabelledAsNotAnError() {
        // grep 无匹配（rc=1 且无输出）不是错误——prompt 里也要如实说明，
        // 否则模型会把"ComfyUI 没在跑"当成命令失败。
        val message = msg(TerminalMessageRole.ASSISTANT, "查一下").copy(
            toolCalls = listOf(
                ToolCall(
                    id = "call-1",
                    command = "ps aux | grep -i \"[c]omfy\"",
                    status = ToolCallStatus.OK,
                    riskTier = RiskTier.READ_ONLY,
                    exitCode = 1,
                    output = "",
                ),
            ),
        )
        val t = AssistantContext.buildTranscript(listOf(message), "然后呢")
        assertTrue("应说明无匹配不是出错", t.text.contains("无匹配"))
    }

    // ===== 压缩计数分类（v0.2.72）=====

    @Test
    fun omittedNotesCountedSeparatelyFromOutputs() {
        val history = buildList {
            repeat(4) { add(longOutput("out$it")) }
            add(msg(TerminalMessageRole.SYSTEM_NOTE, "已排队 1 条"))
            add(msg(TerminalMessageRole.SYSTEM_NOTE, "已省略 2 条"))
            repeat(3) { add(longOutput("recent$it")) }
        }
        val t = AssistantContext.buildTranscript(history, "继续", budgetTokens = 900)
        assertTrue("应触发压缩", t.compacted)
        assertTrue("命令输出计数应大于 0", t.omittedOutputs > 0)
        assertTrue("旁注计数应大于 0", t.omittedNotes > 0)
        val totalOmitted = t.omittedOutputs + t.omittedNotes
        assertEquals(
            "总占位数应等于两类之和",
            totalOmitted,
            Regex(Regex.escape(AssistantContext.OMITTED_OUTPUT)).findAll(t.text).count(),
        )
    }

    // ===== 压缩：不够才动对话 =====

    @Test
    fun dropsOldestTurnsWhenStillOverBudgetAfterOutputCompaction() {
        val history = (1..40).map { msg(TerminalMessageRole.USER, "提问$it " + "y".repeat(500)) }
        val t = AssistantContext.buildTranscript(history, "最新一条", budgetTokens = 800)
        assertTrue("应丢弃较早的对话", t.omittedTurns > 0)
        assertTrue("应留下省略提示", t.text.contains(AssistantContext.OMITTED_TURNS_PREFIX))
        assertTrue("最新的提问必须在", t.text.contains("最新一条"))
        assertTrue("压缩后仍要回到预算内", t.tokens <= AssistantContext.DEFAULT_TOKEN_BUDGET)
    }

    @Test
    fun alwaysKeepsAtLeastRecentMessages() {
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
        assertEquals(4, AssistantContext.estimateTokens("你好世界"))
    }

    @Test
    fun estimatesLatinRoughlyFourCharsPerToken() {
        assertEquals(1, AssistantContext.estimateTokens("abcd"))
        assertEquals(2, AssistantContext.estimateTokens("abcdefgh"))
        assertEquals(0, AssistantContext.estimateTokens(""))
    }
}
