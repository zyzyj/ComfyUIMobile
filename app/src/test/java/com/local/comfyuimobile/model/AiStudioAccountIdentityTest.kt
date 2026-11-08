package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号标识（v0.3.31）。
 *
 * ## 真机 bug
 *
 * 用户有两个 AI Studio 账号，昵称让人完全分不清：
 * - uid=**20222217**，昵称「眨眼睛eye」，英文名 zayanj
 * - uid=**20104163**，昵称「ZAYANJ」
 *
 * AI 把 `zayanj` 当成 20104163 的英文名，于是把项目归错了账号。
 * 根因：工具返回里只给昵称、不给 uid —— 昵称是**用户可改**的显示名，
 * 而 uid 是平台侧唯一不变的数字标识。
 *
 * 规则：**任何给 AI 看的账号标识都必须带 uid**。
 */
class AiStudioAccountIdentityTest {

    private fun account(uid: String, nickname: String) =
        AiStudioAccount(id = "uid:$uid", uid = uid, nickname = nickname)

    @Test
    fun identityAlwaysCarriesUid() {
        // 这两个昵称在真机上让人搞混，但只要带 uid 就不会错。
        val a = account("20222217", "眨眼睛eye")
        val b = account("20104163", "ZAYANJ")

        assertEquals("眨眼睛eye（uid=20222217）", a.identity())
        assertEquals("ZAYANJ（uid=20104163）", b.identity())
        // 关键：两个标识必须不同（只靠昵称有混淆风险）
        assertTrue("不同账号的 identity 必须可区分", a.identity() != b.identity())
    }

    @Test
    fun identityFlagsMissingUid() {
        // uid 缺失时标识不可靠，必须显式提醒（而不是静默只给昵称）。
        val noUid = AiStudioAccount(id = "name:someone", nickname = "someone")
        assertTrue(
            "uid 缺失必须提示：${noUid.identity()}",
            noUid.identity().contains("uid 未知"),
        )
    }

    @Test
    fun identityFallsBackToUidWhenNicknameBlank() {
        val noName = AiStudioAccount(id = "uid:123", uid = "123", nickname = "")
        assertTrue("昵称为空时应显示 uid：${noName.identity()}", noName.identity().contains("123"))
        assertTrue(noName.identity().contains("uid=123"))
    }

    @Test
    fun similarNicknamesAreStillDistinguishable() {
        // 模拟真机：两个账号昵称大小写相近，只差一个字符。
        val a = account("20222217", "zayanj")
        val b = account("20104163", "ZAYANJ")
        // displayName 会撞（忽略大小写时），但 identity 不会。
        assertTrue("仅靠 displayName 有混淆风险", a.displayName().equals(b.displayName(), ignoreCase = true))
        assertFalse("identity 必须能区分", a.identity() == b.identity())
    }
}
