package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.69：多账号执行顺序的单测。
 *
 * 锁住真机 bug 的修复：「每日自动签到」以前只对当前账号跑，第二个账号永远签不到。
 * 改成遍历所有账号后，顺序必须保证当前账号在最前（否则面板状态不刷新）。
 */
class AiStudioAccountOrderTest {

    private fun account(id: String, name: String) = AiStudioAccount(
        id = id,
        uid = id,
        nickname = name,
    )

    @Test
    fun activeAccountGoesFirst() {
        val accounts = listOf(account("a", "甲"), account("b", "乙"), account("c", "丙"))
        val ordered = AiStudioAccountOrder.activeFirst(accounts, "b")
        assertEquals(listOf("b", "a", "c"), ordered.map { it.id })
    }

    @Test
    fun otherAccountsKeepTheirOriginalOrder() {
        // 其余账号保持用户添加的顺序：打乱会让日志与面板对不上
        val accounts = listOf(account("a", "甲"), account("b", "乙"), account("c", "丙"))
        assertEquals(listOf("c", "a", "b"), AiStudioAccountOrder.activeFirst(accounts, "c").map { it.id })
    }

    @Test
    fun allAccountsArePreserved() {
        // 关键：不能漏账号——漏了那个账号就永远签不到（这正是本次要修的 bug）
        val accounts = listOf(account("a", "甲"), account("b", "乙"), account("c", "丙"))
        val ordered = AiStudioAccountOrder.activeFirst(accounts, "a")
        assertEquals(3, ordered.size)
        assertEquals(setOf("a", "b", "c"), ordered.map { it.id }.toSet())
    }

    @Test
    fun singleAccountIsUnchanged() {
        val accounts = listOf(account("a", "甲"))
        assertEquals(listOf("a"), AiStudioAccountOrder.activeFirst(accounts, "a").map { it.id })
    }

    @Test
    fun emptyListIsUnchanged() {
        assertTrue(AiStudioAccountOrder.activeFirst(emptyList(), "a").isEmpty())
    }

    @Test
    fun unknownActiveIdLeavesOrderAlone() {
        // 当前 id 不在列表里（已删除）：保持原样，别把第一个当成 active
        val accounts = listOf(account("a", "甲"), account("b", "乙"))
        assertEquals(listOf("a", "b"), AiStudioAccountOrder.activeFirst(accounts, "zzz").map { it.id })
    }

    @Test
    fun blankActiveIdLeavesOrderAlone() {
        val accounts = listOf(account("a", "甲"), account("b", "乙"))
        assertEquals(listOf("a", "b"), AiStudioAccountOrder.activeFirst(accounts, null).map { it.id })
        assertEquals(listOf("a", "b"), AiStudioAccountOrder.activeFirst(accounts, "").map { it.id })
    }
}
