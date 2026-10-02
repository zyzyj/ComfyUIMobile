package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.68：项目状态变化判定的单测。
 *
 * 锁住真机 bug 的触发条件：项目被平台回收后必须能被识别出来，
 * 否则终端保活通知会一直挂在通知栏上。
 */
class AiStudioProjectPolicyTest {

    private fun project(id: String, running: Boolean) =
        AiStudioProject(projectId = id, name = "项目$id", running = running)

    @Test
    fun detectsProjectThatJustStopped() {
        // 真机场景：启动 GPU 后挂后台，平台把机器收回，刷新时 running 变成 false
        val before = listOf(project("10707054", running = true))
        val after = listOf(project("10707054", running = false))
        assertEquals(listOf("10707054"), AiStudioProjectPolicy.justStopped(before, after))
    }

    @Test
    fun ignoresProjectsThatWereAlreadyStopped() {
        // 关键：不能把所有 running=false 都当成"刚停"——列表里大部分项目本来就没跑，
        // 那样会误触发终端与保活的清理。
        val before = listOf(project("a", running = false), project("b", running = false))
        val after = listOf(project("a", running = false), project("b", running = false))
        assertTrue(AiStudioProjectPolicy.justStopped(before, after).isEmpty())
    }

    @Test
    fun ignoresProjectsThatKeepRunning() {
        val before = listOf(project("a", running = true))
        val after = listOf(project("a", running = true))
        assertTrue(AiStudioProjectPolicy.justStopped(before, after).isEmpty())
    }

    @Test
    fun onlyReportsTheProjectThatChanged() {
        // 多项目并存：只该清理真正停掉的那个，别碰还在跑的
        val before = listOf(project("a", running = true), project("b", running = true))
        val after = listOf(project("a", running = false), project("b", running = true))
        assertEquals(listOf("a"), AiStudioProjectPolicy.justStopped(before, after))
    }

    @Test
    fun treatsDisappearedProjectAsStopped() {
        // 项目从列表里消失（被删除）——机器同样没了，也要清理
        val before = listOf(project("a", running = true))
        assertEquals(listOf("a"), AiStudioProjectPolicy.justStopped(before, emptyList()))
    }

    @Test
    fun newlyStartedProjectIsNotReported() {
        // 反向：从停止变运行不该被判为"刚停"（避免方向写反）
        val before = listOf(project("a", running = false))
        val after = listOf(project("a", running = true))
        assertTrue(AiStudioProjectPolicy.justStopped(before, after).isEmpty())
    }

    @Test
    fun emptyBeforeMeansNothingToClean() {
        assertTrue(AiStudioProjectPolicy.justStopped(emptyList(), listOf(project("a", running = false))).isEmpty())
    }
}
