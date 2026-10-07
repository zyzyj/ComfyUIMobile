package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.model.AiStudioAccount
import com.local.comfyuimobile.model.AiStudioProject
import com.local.comfyuimobile.network.AiStudioKernelClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `terminal_exec` 的终端归属与缓冲区归属（v0.2.98，修 P0-1）。
 *
 * 为什么这两个用例非写不可：P0-1 是"传一个不存在的终端名 → 100% 卡到超时"，
 * 而项目原有 65 个测试全过——**因为没有任何测试覆盖"标记写进哪个缓冲区"**。
 * 这类 bug 只能靠注入假后端、直接断言缓冲区来抓。
 *
 * 复现路径（修前）：
 *   `exec("echo hi", terminal = "probe")` → ensureSession 新建随机名 `1`、
 *   输出写进 `1` 的缓冲区；而 exec 读的是 `probe` 的缓冲区 → 永远等不到标记。
 */
class McpTerminalHostTest {

    private val account = AiStudioAccount(id = "a1", nickname = "tester")
    private val project = AiStudioProject(projectId = "p1", name = "demo", running = true)

    /**
     * 纯内存假后端：不联网、不需要 Android 环境。
     *
     * 它模拟 PTY 的三件事：回显命令行、输出正文、输出结束标记。
     */
    private class FakeBackend(
        private val remote: MutableList<String> = mutableListOf("default"),
        private val newNames: List<String> = listOf("1", "2", "3"),
    ) : TerminalBackend {

        private val sessions = LinkedHashMap<String, AiStudioKernelClient.TerminalSession>()
        private val outputs = LinkedHashMap<String, (String) -> Unit>()
        private var nameIndex = 0

        /** 记录每条命令发到了哪个终端名。 */
        val sentTo = mutableListOf<String>()

        /** 记录 openTerminal 是否要求成为"界面当前终端"。 */
        val asUiCurrentFlags = mutableListOf<Boolean>()
        var createdCount = 0

        override suspend fun fetchEndpoint(
            account: AiStudioAccount,
            projectId: String,
            scheduleName: String,
        ) = AiStudioKernelClient.KernelEndpoint(
            baseUrl = "https://example.test/user",
            basePath = "/user",
            token = "t",
        )

        override suspend fun listTerminals(
            account: AiStudioAccount,
            endpoint: AiStudioKernelClient.KernelEndpoint,
        ): List<String> = remote.toList()

        override suspend fun createTerminal(
            account: AiStudioAccount,
            endpoint: AiStudioKernelClient.KernelEndpoint,
        ): String {
            val name = newNames.getOrElse(nameIndex) { "n$nameIndex" }
            nameIndex++
            remote += name
            createdCount++
            return name
        }

        override fun session(name: String): AiStudioKernelClient.TerminalSession? = sessions[name]

        override fun sessionNames(): List<String> = sessions.keys.toList()

        override fun openTerminal(
            account: AiStudioAccount,
            endpoint: AiStudioKernelClient.KernelEndpoint,
            name: String,
            asUiCurrent: Boolean,
            onOutput: (String) -> Unit,
            onOpen: () -> Unit,
            onClosed: (String) -> Unit,
        ) {
            asUiCurrentFlags += asUiCurrent
            outputs[name] = onOutput
            sessions[name] = AiStudioKernelClient.TerminalSession(name, "conn-$name", null)
            onOpen()
        }

        override fun sendInput(command: String, session: AiStudioKernelClient.TerminalSession?): Boolean {
            val target = session ?: return false
            sentTo += target.name
            val emit = outputs[target.name] ?: return true
            // 真实 PTY 会先回显整行命令，然后才是命令输出。
            emit(command + "\r\n")
            emit("hello\r\n")
            // command 形如 `echo hi; echo "__CM_<token>:$?"`，取出标记回一个结果行。
            val mark = command.substringAfter("\"").substringBefore(":")
            emit("$mark:0\r\n")
            return true
        }

        override fun sendRawInput(raw: String, session: AiStudioKernelClient.TerminalSession?): Boolean =
            session != null

        override fun resize(cols: Int, rows: Int, session: AiStudioKernelClient.TerminalSession?) = Unit

        override suspend fun warmUpProjectCookies(
            account: AiStudioAccount,
            endpoint: AiStudioKernelClient.KernelEndpoint,
        ) = Unit

        override fun exportCookies(): String = ""
    }

    private fun host(backend: FakeBackend) = McpTerminalHost(
        kernel = backend,
        activeProject = { account to project },
    )

    @Test
    fun execOnUnknownTerminalNameStillReturnsOutput() = runBlocking {
        // P0-1 的核心用例：名字不存在时**必须**通过新建的终端拿到输出，而不是等超时。
        val backend = FakeBackend()
        val text = withTimeout(10_000) {
            host(backend).exec("echo hi", terminal = "probe", timeoutSeconds = 5)
        }
        assertFalse("不能是超时文案：$text", text.contains("没有结束"))
        assertTrue("应拿到命令输出：$text", text.contains("hello"))
        assertTrue("应报出退出码：$text", text.contains("退出码 0"))
    }

    @Test
    fun execOnUnknownTerminalReportsActualName() = runBlocking {
        // 必须告诉 AI 实际用了哪个终端，否则它下次还传 probe，每次都新建一条。
        val backend = FakeBackend()
        val text = withTimeout(10_000) {
            host(backend).exec("echo hi", terminal = "probe", timeoutSeconds = 5)
        }
        assertTrue("应说明换了终端：$text", text.contains("probe"))
        assertTrue("应给出实际终端名：$text", text.contains("1"))
        // 命令确实发到了新建的那条上。
        assertEquals(listOf("1"), backend.sentTo)
    }

    @Test
    fun execReusesExistingTerminalWithoutRenamingNotice() = runBlocking {
        // 名字存在时不该出现"已新建"的提示，也不该新建终端。
        val backend = FakeBackend()
        val text = withTimeout(10_000) {
            host(backend).exec("echo hi", terminal = "default", timeoutSeconds = 5)
        }
        assertFalse("不该提示改名：$text", text.contains("不存在"))
        assertEquals(0, backend.createdCount)
        assertTrue(text, text.contains("hello"))
    }

    @Test
    fun mcpNeverClaimsToBeUiCurrentTerminal() = runBlocking {
        // P0-2 的 MCP 侧：AI 连终端**绝不能**把自己设成"界面当前终端"，
        // 否则用户在控制台敲的命令会发到 AI 的终端去。
        val backend = FakeBackend()
        withTimeout(10_000) { host(backend).exec("echo hi", terminal = "default", timeoutSeconds = 5) }
        assertTrue("应调用过 openTerminal", backend.asUiCurrentFlags.isNotEmpty())
        assertTrue("MCP 必须传 false：${backend.asUiCurrentFlags}", backend.asUiCurrentFlags.all { !it })
    }

    @Test
    fun secondCallOnSameNameReusesSession() = runBlocking {
        // 第一次会新建，第二次应复用，不再新建。
        val backend = FakeBackend()
        val h = host(backend)
        withTimeout(10_000) { h.exec("echo one", terminal = "probe", timeoutSeconds = 5) }
        withTimeout(10_000) { h.exec("echo two", terminal = "probe", timeoutSeconds = 5) }
        assertEquals("第二次不该再新建", 1, backend.createdCount)
    }
}
