package com.local.comfyuimobile.network

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.60：AI 终端助手内置守则的单测。
 *
 * 这组测试锁的是**行为约束**——用户明确反馈过“AI 一上来就直接执行命令，不先查 GPU 状态”，
 * 所以守则里必须始终存在“先侦察”这条硬要求。一旦有人把它删了，这里会红。
 */
class TerminalPlaybookTest {

    private fun prompt(
        projectName: String = "",
        gpuLabel: String = "",
        comfyUiUrl: String = "",
        comfyUiConnected: Boolean = false,
        terminalConnected: Boolean = true,
    ) = TerminalPlaybook.systemPrompt(
        TerminalPlaybook.EnvironmentFacts(
            projectName = projectName,
            gpuLabel = gpuLabel,
            comfyUiUrl = comfyUiUrl,
            comfyUiConnected = comfyUiConnected,
            terminalConnected = terminalConnected,
        ),
    )

    // ===== 守则骨架 =====

    @Test
    fun playbookAlwaysContainsInspectionRequirement() {
        // 用户反馈的核心问题：不先侦察就动手。这条不能被删。
        val text = prompt()
        assertTrue("守则应要求先侦察", text.contains("侦察"))
        assertTrue("应明确 nvidia-smi（查 GPU）", text.contains("nvidia-smi"))
        assertTrue("应查磁盘余量", text.contains("df -h"))
        // 守则里的实际写法是 `ps aux | grep -i "[c]omfy"` —— [c] 是防 grep 自匹配
        // 的惯用技巧（不加的话 grep 进程本身也会被列出来）。所以不能按字面量查 "comfy"，
        // 改查命令与意图关键词。
        assertTrue("应查 ComfyUI 是否已在跑", text.contains("ps aux"))
        assertTrue("应说明查的是是否已在运行", text.contains("已在运行"))
    }

    @Test
    fun playbookCoversAllFivePhases() {
        val text = prompt()
        listOf("侦察", "诊断", "计划", "执行", "验证").forEach { phase ->
            assertTrue("守则缺少阶段：$phase", text.contains(phase))
        }
    }

    @Test
    fun playbookWarnsAgainstUnsafeOperations() {
        val text = prompt()
        assertTrue(text.contains("rm -rf"))
        assertTrue("应提醒别打断正在跑的任务", text.contains("重启"))
    }

    @Test
    fun playbookRequiresOneCommandPerBlock() {
        val text = prompt()
        assertTrue("应要求一次只给一条命令", text.contains("一条命令"))
    }

    @Test
    fun playbookForbidsClaimingExecution() {
        // AI 只能提议，不能声称已执行——这是 App 侧人工关卡的前提。
        val text = prompt()
        assertTrue(text.contains("提议"))
    }

    // ===== 环境事实注入 =====

    @Test
    fun knownFactsAreInjectedVerbatim() {
        val text = prompt(
            projectName = "comfyui",
            gpuLabel = "V100 16GB",
            comfyUiUrl = "https://aistudio.baidu.com/user/1/2/api_serving/8188",
        )
        // App 已经知道的事实要直接给出，别让 AI 浪费一轮去探
        assertTrue(text.contains("comfyui"))
        assertTrue(text.contains("V100 16GB"))
        assertTrue(text.contains("api_serving/8188"))
    }

    @Test
    fun unknownGpuTellsModelToProbe() {
        // 拿不到档位时要明确提示可先探——总比让 AI 猜好
        val text = prompt(gpuLabel = "")
        assertTrue(text.contains("未知"))
        assertTrue(text.contains("nvidia-smi"))
    }

    @Test
    fun connectionStatesAreReported() {
        val connected = prompt(comfyUiConnected = true, terminalConnected = true)
        assertTrue(connected.contains("App 已连接"))
        assertTrue(connected.contains("可执行命令"))

        val disconnected = prompt(comfyUiConnected = false, terminalConnected = false)
        assertTrue(disconnected.contains("未连接"))
        assertTrue(disconnected.contains("命令无法执行"))
    }

    // ===== 环境块不产生噪音 =====

    @Test
    fun environmentBlockOmitsBlankOptionalLines() {
        val block = TerminalPlaybook.environmentBlock(TerminalPlaybook.EnvironmentFacts())
        // 项目名/地址为空时不该输出“AI Studio 项目：”这种空壳行
        assertTrue(!block.contains("AI Studio 项目：\n"))
        assertTrue(!block.contains("ComfyUI 地址：\n"))
    }

    @Test
    fun systemPromptHasNoLeadingOrTrailingBlankLines() {
        val text = prompt()
        assertTrue(text.isNotBlank())
        assertTrue(!text.startsWith("\n"))
        assertTrue(!text.endsWith("\n"))
    }
}
