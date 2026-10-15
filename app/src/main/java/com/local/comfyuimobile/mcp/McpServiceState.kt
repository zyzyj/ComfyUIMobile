package com.local.comfyuimobile.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MCP 服务的**唯一**状态真相（v0.3.9）。
 *
 * ## 修的是什么
 *
 * 真机反馈：「关掉 MCP 再打开，是假打开——开关显示开、通知栏没有卡片」。
 *
 * 根因是一条竞态：
 * ```
 * setMcpServerEnabled(true)
 *   ├─ McpServerService.start(app, token)   // ① 同步发起，服务立刻启动
 *   └─ viewModelScope.launch { 落盘 enabled=true }  // ② 异步，还没写完
 * ```
 * 而 `McpServerService.startServer` **读磁盘**判断该不该跑：
 * ```
 * if (stored?.mcpServerEnabled != true) { stopServer(NOT_ENABLED) }
 * ```
 * ①（同步）几乎必然早于 ②（异步）→ 服务读到**上一次的 false** → 立刻自停。
 * 而界面开关读的是内存意图（true），于是「开关亮着、服务已死、通知没了」。
 *
 * 这不是新病：v0.2.46 给账号字段、v0.3.5 给 MCP 开关都做过「内存领先磁盘」，
 * 但只补了 ViewModel 一侧，**服务侧的启动判据仍读磁盘**——修了一半。
 *
 * ## 为什么是单例而不是给两边各注入一份
 *
 * 服务与 ViewModel 是两条独立装配路径（一个在 Service、一个在 ViewModel），
 * 各注入一份就是"同一件事两份状态"，这正是本项目已犯 12 次的平行路径。
 * 进程内单例是**唯一**能让两边看到同一份真相的做法（与 [com.local.comfyuimobile.network.TerminalOwnership] 同一思路）。
 *
 * ## 两个字段的含义（别混）
 *
 * - [intent]：**用户想要它开吗**。内存权威，落盘只是持久化。服务启动判据必须读它。
 * - [status]：**它这一刻真的活着吗**。由服务自己上报，界面与自检都读它。
 *
 * 以前界面用 `mcpServerPort > 0`（乐观值）代替 status、自检读 `isRunning()`（另一个
 * 静态标记），三者各说一套——现在统一到 [status]。
 */
object McpServiceState {

    /** 服务运行状态。 */
    sealed interface Status {
        /** 没在跑（含从没启动过）。 */
        data object Stopped : Status

        /** 已监听，[port] 是真实绑定端口。 */
        data class Running(val port: Int) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Stopped)

    /** 真实运行状态流。界面订阅它，不再各自猜。 */
    val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * 用户意图（v0.3.9，修「假打开」）。
     *
     * null = 还没人表过态，此时**回退读磁盘**（进程刚启动、还没进过 MCP 页的情形）。
     * 一旦用户点过开关，就以它为准——磁盘写入是异步的，不能拿它判"现在该不该跑"。
     */
    @Volatile
    var intent: Boolean? = null

    /** 这一刻真的在跑吗（[status] 的同步快照）。 */
    fun isRunning(): Boolean = _status.value is Status.Running

    /** 真实绑定端口；没跑时为 0。 */
    fun runningPort(): Int = (_status.value as? Status.Running)?.port ?: 0

    /** 服务上报：已就绪。 */
    fun markRunning(port: Int) {
        _status.value = Status.Running(port)
    }

    /** 服务上报：已停止（用户关、启动失败、被系统回收都走这里）。 */
    fun markStopped() {
        _status.value = Status.Stopped
    }

    /**
     * 该不该启动服务。**意图优先，磁盘兜底**。
     *
     * 供 `McpServerService.startServer` 用——它就是那个"读磁盘"的判据，
     * 改成走这里才修得掉「假打开」。
     */
    fun shouldRun(storedEnabled: Boolean): Boolean = intent ?: storedEnabled
}
