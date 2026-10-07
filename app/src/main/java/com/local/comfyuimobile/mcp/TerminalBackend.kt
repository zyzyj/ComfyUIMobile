package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.model.AiStudioAccount
import com.local.comfyuimobile.network.AiStudioKernelClient

/**
 * 终端后端（v0.2.98）：[McpTerminalHost] 真正依赖的一组能力。
 *
 * 存在的理由很具体：P0-1（传不存在的终端名 → 100% 卡到超时）与
 * P0-2（AI 开终端顶掉界面那条）之所以能溜过 65 个测试，是因为这层能力直接绑在
 * [AiStudioKernelClient] 上（要网络、要 Android 环境），没有接缝可以注入假实现。
 *
 * 抽成接口后，`McpTerminalHost` 可以用一个纯内存的假后端做单测——
 * **\"标记写进哪个缓冲区\"正是 P0-1 的核心，必须能被断言**。
 */
internal interface TerminalBackend {

    suspend fun fetchEndpoint(
        account: AiStudioAccount,
        projectId: String,
        scheduleName: String,
    ): AiStudioKernelClient.KernelEndpoint

    suspend fun listTerminals(account: AiStudioAccount, endpoint: AiStudioKernelClient.KernelEndpoint): List<String>

    suspend fun createTerminal(account: AiStudioAccount, endpoint: AiStudioKernelClient.KernelEndpoint): String

    fun session(name: String): AiStudioKernelClient.TerminalSession?

    fun sessionNames(): List<String>

    fun openTerminal(
        account: AiStudioAccount,
        endpoint: AiStudioKernelClient.KernelEndpoint,
        name: String,
        asUiCurrent: Boolean,
        onOutput: (String) -> Unit,
        onOpen: () -> Unit,
        onClosed: (String) -> Unit,
    )

    fun sendInput(command: String, session: AiStudioKernelClient.TerminalSession? = null): Boolean

    fun sendRawInput(raw: String, session: AiStudioKernelClient.TerminalSession? = null): Boolean

    fun resize(cols: Int, rows: Int, session: AiStudioKernelClient.TerminalSession? = null)

    suspend fun warmUpProjectCookies(account: AiStudioAccount, endpoint: AiStudioKernelClient.KernelEndpoint)

    fun exportCookies(): String
}
