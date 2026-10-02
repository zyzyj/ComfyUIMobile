package com.local.comfyuimobile.model

/**
 * AI Studio 项目状态变化的判定（v0.2.68）。
 *
 * 抽成纯函数是因为一个真机 bug：项目被平台回收后，App 既没断终端、也没撤保活通知，
 * 通知栏一直挂着「已连接 AI Studio 终端 · 保持连接中」。
 * 判定"谁从运行变停了"是这个收尾动作的触发条件，值得单独锁住。
 */
object AiStudioProjectPolicy {

    /**
     * 比对刷新前后两份项目列表，找出**刚刚从运行变为停止**的项目 id。
     *
     * 为什么要"前后对比"而不是直接看 `running == false`：
     * 列表里大部分项目本来就没在跑，直接看 false 会把它们全部当成"刚停"，
     * 从而误触发终端与保活的清理（多项目并存时尤其危险）。
     * 只有"上次在跑、这次不在跑"才是真正的状态变化。
     *
     * 停掉的项目若从列表里消失（被删除），也算停止——机器同样没了。
     */
    fun justStopped(before: List<AiStudioProject>, after: List<AiStudioProject>): List<String> {
        val wasRunning = before.filter { it.running }.mapTo(mutableSetOf()) { it.projectId }
        if (wasRunning.isEmpty()) return emptyList()
        val stillRunning = after.filter { it.running }.mapTo(mutableSetOf()) { it.projectId }
        return wasRunning.filterNot { it in stillRunning }
    }
}
