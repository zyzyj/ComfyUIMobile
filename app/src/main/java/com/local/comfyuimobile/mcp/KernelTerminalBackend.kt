package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.model.AiStudioAccount
import com.local.comfyuimobile.network.AiStudioKernelClient

/**
 * [TerminalBackend] 的真实实现：转发给 [AiStudioKernelClient]（v0.2.98）。
 *
 * 为什么用适配器而不是让 client 直接 implements：`AiStudioKernelClient` 是 public，
 * 而 Kotlin 不允许 public 类暴露 internal 超类型。适配器放 mcp 包内（internal），
 * 两头都不用改可见性，也不必让 client 认识 mcp 这层。
 */
internal class KernelTerminalBackend(
    private val kernel: AiStudioKernelClient,
) : TerminalBackend {

    override suspend fun fetchEndpoint(
        account: AiStudioAccount,
        projectId: String,
        scheduleName: String,
    ): AiStudioKernelClient.KernelEndpoint = kernel.fetchEndpoint(account, projectId, scheduleName)

    override suspend fun listTerminals(
        account: AiStudioAccount,
        endpoint: AiStudioKernelClient.KernelEndpoint,
    ): List<String> = kernel.listTerminals(account, endpoint)

    override suspend fun createTerminal(
        account: AiStudioAccount,
        endpoint: AiStudioKernelClient.KernelEndpoint,
    ): String = kernel.createTerminal(account, endpoint)

    override fun session(name: String): AiStudioKernelClient.TerminalSession? = kernel.session(name)

    override fun sessionNames(): List<String> = kernel.sessionNames()

    override fun openTerminal(
        account: AiStudioAccount,
        endpoint: AiStudioKernelClient.KernelEndpoint,
        name: String,
        asUiCurrent: Boolean,
        onOutput: (String) -> Unit,
        onOpen: () -> Unit,
        onClosed: (String) -> Unit,
    ) = kernel.openTerminal(account, endpoint, name, asUiCurrent, onOutput, onOpen, onClosed)

    override fun sendInput(command: String, session: AiStudioKernelClient.TerminalSession?): Boolean =
        kernel.sendInput(command, session)

    override fun sendRawInput(raw: String, session: AiStudioKernelClient.TerminalSession?): Boolean =
        kernel.sendRawInput(raw, session)

    override fun resize(cols: Int, rows: Int, session: AiStudioKernelClient.TerminalSession?) =
        kernel.resize(cols, rows, session)

    override suspend fun warmUpProjectCookies(
        account: AiStudioAccount,
        endpoint: AiStudioKernelClient.KernelEndpoint,
    ) = kernel.warmUpProjectCookies(account, endpoint)

    override fun exportCookies(): String = kernel.exportCookies()
}
