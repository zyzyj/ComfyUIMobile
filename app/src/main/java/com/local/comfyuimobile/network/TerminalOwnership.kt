package com.local.comfyuimobile.network

import java.util.concurrent.ConcurrentHashMap

/**
 * 终端归属的进程内登记（v0.3.8，P0-3）。
 *
 * ## 为什么需要它
 *
 * UI 与 MCP 各有自己的 `AiStudioKernelClient`（`MainViewModel:220` 与
 * `McpServerService:156` 各 new 一个），**与 UI 不共用**——所以 UI 看不见 AI 开的终端，
 * 这不是配置问题，是结构问题。
 *
 * 而平台侧没有任何字段能判断某条 PTY 是谁开的。既然**无法从数据判断归属**，
 * 就改成**在进程内自己记**：谁建的谁登记，UI 只信自己那条，其余一律不复用。
 *
 * ## 为什么是单例而不是注入
 *
 * UI 与 MCP 是两条独立装配路径（一个在 ViewModel、一个在 Service），
 * 各自注入会让"同一件事两份状态"再次出现——本项目已犯 12 次平行路径。
 * 进程内单例是**唯一**能让两边看到同一份归属的做法。
 *
 * ## 为什么不落盘
 *
 * 进程死了会话也没了，落盘只会留下指向不存在终端的陈旧标记。
 */
object TerminalOwnership {

    /** UI 侧登记的终端名（同一时刻最多一条）。 */
    @Volatile private var uiName: String? = null

    /** MCP 侧登记过的终端名（可能多条：不同终端名对应不同会话）。 */
    private val mcpNames = ConcurrentHashMap.newKeySet<String>()

    fun ownUi(name: String) {
        uiName = name
    }

    fun ui(): String? = uiName

    fun clearUi() {
        uiName = null
    }

    fun ownMcp(name: String) {
        mcpNames += name
    }

    fun clearMcp(name: String) {
        mcpNames -= name
    }

    fun clearAllMcp() {
        mcpNames.clear()
    }

    /** MCP 侧当前登记的名字（快照）。 */
    fun mcpSnapshot(): Set<String> = mcpNames.toSet()

    /**
     * 平台返回的终端列表里，哪些是 **MCP（AI）在用的**。
     *
     * 给 UI 展示用：用户能一眼看到"AI 在用哪条"，而不是把 AI 的日志当成自己的。
     * 排除 UI 自己那条——即使名字撞了，也不该把它标成 AI 的。
     */
    fun aiOwnedAmong(remoteNames: List<String>): List<String> =
        remoteNames.filter { it != uiName && it in mcpNames }
}
