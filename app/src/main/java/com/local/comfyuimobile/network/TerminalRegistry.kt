package com.local.comfyuimobile.network

import java.util.concurrent.ConcurrentHashMap

/**
 * 终端会话注册表（v0.2.98）：谁是谁，以及**哪一个是"界面在用的那一个"**。
 *
 * 为什么单独抽出来：P0-2（AI 一开终端就把界面那条顶掉，用户在控制台敲命令发去了
 * AI 的终端、自己那条永远没输出）能溜过 65 个测试，正是因为**没有任何测试覆盖
 * "当前终端归谁"**。把这层逻辑做成不依赖 OkHttp 的纯类，就可以直接断言。
 *
 * 关键约定：**只有显式 [setUi] 才会改"界面在用的那条"**。以前 `openTerminal`
 * 无条件设置，于是谁最后开谁说了算。
 */
internal class TerminalRegistry {

    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    /** 界面控制台当前用的终端名。MCP 侧**永远不该**改它。 */
    @Volatile private var uiName: String? = null

    fun put(session: TerminalSession) {
        sessions[session.name] = session
    }

    fun get(name: String): TerminalSession? = sessions[name]

    /** 摘掉某名字的会话（返回被摘掉的那个，没有则 null）。 */
    fun remove(name: String): TerminalSession? {
        val removed = sessions.remove(name) ?: return null
        removed.closed = true
        if (uiName == name) uiName = null
        return removed
    }

    /**
     * 仅当表里装的**还是这个会话实例**时才摘掉。
     *
     * 重连会先建新会话再让旧 socket 的回调迟到；不比对实例的话，旧回调的
     * detach 会把刚连上的新会话一起删掉。
     */
    fun removeIfSame(session: TerminalSession) {
        if (sessions[session.name] === session) {
            sessions.remove(session.name)
            session.closed = true
            if (uiName == session.name) uiName = null
        }
    }

    fun names(): List<String> = sessions.keys.toList()

    /** 标记为"界面正在用的终端"。**只有界面该调它**。 */
    fun setUi(session: TerminalSession) {
        uiName = session.name
    }

    fun uiSession(): TerminalSession? = uiName?.let { sessions[it] }

    /** 关掉全部会话并清空注册表（切账号 / 退出 App）。 */
    fun clear() {
        sessions.values.forEach { it.closed = true }
        sessions.clear()
        uiName = null
    }
}
