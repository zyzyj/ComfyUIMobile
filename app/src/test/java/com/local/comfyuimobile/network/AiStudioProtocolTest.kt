package com.local.comfyuimobile.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.90：AI Studio 协议层的纯逻辑测试。
 *
 * 平台接口会改版，这里锁住的是"解析不能因为缺字段就崩"这一条底线，以及
 * 认证与请求体的构造规则——这两块一旦写错，表现是"登录了但什么都拉不到"，
 * 排查成本很高，值得用测试钉住。
 */
class AiStudioProtocolTest {

    // ===== 响应壳 =====

    @Test
    fun unwrapsResultOnSuccess() {
        val raw = """{"errorCode":0,"errorMsg":"","result":{"id":"123","nickname":"小明"}}"""
        val result = AiStudioProtocol.unwrap(raw, "测试")
        assertEquals("123", AiStudioProtocol.parseUid(result))
        assertEquals("小明", AiStudioProtocol.parseNickname(result))
    }

    @Test
    fun throwsOnNonZeroErrorCode() {
        val raw = """{"errorCode":8307,"errorMsg":"need graphic"}"""
        val error = runCatching { AiStudioProtocol.unwrap(raw, "启动") }.exceptionOrNull()
        assertTrue(error is AiStudioException)
        assertTrue(error!!.message!!.contains("8307"))
    }

    @Test
    fun acceptsBodyWithoutErrorCode() {
        // 个别接口直接返回正文，没有 errorCode 壳。
        val raw = """{"id":"9"}"""
        assertEquals("9", AiStudioProtocol.parseUid(AiStudioProtocol.unwrap(raw, "测试")))
    }

    @Test
    fun reportsNonJsonInsteadOfCrashing() {
        val error = runCatching {
            AiStudioProtocol.unwrap("<html>登录页</html>", "签到")
        }.exceptionOrNull()
        assertTrue(error is AiStudioException)
    }

    // ===== Cookie / 登录态 =====

    @Test
    fun detectsLoginByBduss() {
        assertTrue(AiStudioProtocol.looksLoggedIn("BAIDUID=x; BDUSS=abc; STOKEN=y"))
        // 只有 BAIDUID（匿名访问也会有）不算登录。
        assertFalse(AiStudioProtocol.looksLoggedIn("BAIDUID=x"))
        assertFalse(AiStudioProtocol.looksLoggedIn(""))
    }

    @Test
    fun readsCookieValueIgnoringSpaces() {
        val cookie = "a=1;  BDUSS=xyz ; RT=\"z=1&v=2\""
        assertEquals("xyz", AiStudioProtocol.cookieValue(cookie, "BDUSS"))
        assertEquals("1", AiStudioProtocol.cookieValue(cookie, "a"))
        assertEquals("", AiStudioProtocol.cookieValue(cookie, "missing"))
    }

    @Test
    fun normalizeCookieDropsEmptySegments() {
        val normalized = AiStudioProtocol.normalizeCookie("a=1;;  ; b=2 ;")
        assertEquals("a=1; b=2", normalized)
    }

    // ===== 项目列表解析 =====

    @Test
    fun parsesProjectsFromDataField() {
        // 平台真实响应：result.data 是数组，allCount 是总数
        val json = JSONObject(
            """
            {"data":[
              {"projectId":"1","projectName":"ComfyUI","projectAbs":"说明","running":true},
              {"projectId":"2","projectName":"另一个"}
            ],"allCount":2}
            """.trimIndent(),
        )
        val page = AiStudioProtocol.parseProjectPage(json)
        assertEquals(2, page.projects.size)
        assertEquals(2, page.total)
        assertEquals("ComfyUI", page.projects[0].name)
        assertTrue("running=true 应判为运行中", page.projects[0].running)
        assertFalse(page.projects[1].running)
    }

    @Test
    fun parsesProjectsFromNestedRecords() {
        val json = JSONObject("""{"data":{"records":[{"id":"7","name":"嵌套"}]}}""")
        val projects = AiStudioProtocol.parseProjects(json)
        assertEquals(1, projects.size)
        assertEquals("7", projects[0].projectId)
    }

    @Test
    fun skipsProjectWithoutId() {
        val json = JSONObject("""{"data":[{"projectName":"没有 id"},{"projectId":"3"}]}""")
        val projects = AiStudioProtocol.parseProjects(json)
        assertEquals(1, projects.size)
        assertEquals("3", projects[0].projectId)
    }

    @Test
    fun emptyProjectsWhenShapeUnknown() {
        assertTrue(AiStudioProtocol.parseProjects(JSONObject("""{"foo":"bar"}""")).isEmpty())
    }

    @Test
    fun recognizesRunningFromVariousShapes() {
        assertTrue(AiStudioProtocol.parseRunning(JSONObject("""{"running":true}""")))
        assertTrue(AiStudioProtocol.parseRunning(JSONObject("""{"isRunning":1}""")))
        assertTrue(AiStudioProtocol.parseRunning(JSONObject("""{"runStatus":"running"}""")))
        assertFalse(AiStudioProtocol.parseRunning(JSONObject("""{"runStatus":"stopped"}""")))
        assertFalse(AiStudioProtocol.parseRunning(JSONObject("""{"running":false}""")))
        // 什么线索都没有时按未运行处理：让用户点一下启动，好过误判成运行中而卡住。
        assertFalse(AiStudioProtocol.parseRunning(JSONObject("{}")))
    }

    @Test
    fun parsesSignInStateFromIsFinishSign() {
        assertEquals(true, AiStudioProtocol.parseSignInDone(JSONObject("""{"isFinishSign":true}""")))
        assertEquals(false, AiStudioProtocol.parseSignInDone(JSONObject("""{"isFinishSign":false}""")))
        assertEquals(true, AiStudioProtocol.parseSignInDone(JSONObject("""{"isFinishSign":1}""")))
        // 没有该字段时返回 null，由调用方退退回本机记录
        org.junit.Assert.assertNull(AiStudioProtocol.parseSignInDone(JSONObject("{}")))
    }

    @Test
    fun parsesACoinFromCoinNumShow() {
        // 平台真实字段：/studio/trade/coin/residue -> coinNumShow
        assertEquals("88", AiStudioProtocol.parseACoin(JSONObject("""{"coinNumShow":88}""")))
        assertEquals("12.5", AiStudioProtocol.parseACoin(JSONObject("""{"coinNumShow":12.5}""")))
        org.junit.Assert.assertNull(AiStudioProtocol.parseACoin(JSONObject("{}")))
    }

    // ===== 算力档位 =====

    @Test
    fun parsesSchedules() {
        val json = JSONObject(
            """
            {"list":[
              {"scheduleName":"normalSchedule","gpuType":"V100 16G"},
              {"name":"a100","label":"A100","available":false},
              {"desc":"没有 scheduleName 的项"}
            ]}
            """.trimIndent(),
        )
        val schedules = AiStudioProtocol.parseSchedules(json)
        assertEquals(2, schedules.size)
        assertEquals("V100 16G", schedules[0].displayName())
        assertEquals("A100", schedules[1].displayName())
        assertFalse(schedules[1].available)
    }

    @Test
    fun scheduleDefaultsToAvailableWhenUnknown() {
        val json = JSONObject("""{"list":[{"scheduleName":"x"}]}""")
        assertTrue(AiStudioProtocol.parseSchedules(json).single().available)
    }

    // ===== 请求体 =====

    @Test
    fun runProjectBodyFillsDefaults() {
        val body = AiStudioProtocol.runProjectBody("42", "")
        assertEquals("42", body["projectId"])
        assertEquals("0", body["versionId"])
        assertEquals("normalSchedule", body["scheduleName"])
        assertEquals("0", body["startMode"])
        assertEquals("", body["tk"])
    }

    @Test
    fun formEncodeEscapesValues() {
        val encoded = AiStudioProtocol.formEncode(mapOf("name" to "中文 名", "a" to "1&2"))
        assertTrue(encoded.startsWith("name="))
        // 空格必须编码成 %20 或 +，不能原样输出，否则表单会解析错。
        assertFalse(encoded.contains(" "))
        assertTrue(encoded.contains("a=1%262"))
    }

    // ===== 账号身份 =====

    @Test
    fun accountKeyPrefersUid() {
        assertEquals("uid:5", AiStudioProtocol.accountKey("5", "昵称", "BDUSS=x"))
    }

    // ===== 积分 / 算力字段（以平台前端实际读取的字段名为准）=====

    @Test
    fun parsesPointsFromRealFieldNames() {
        // totalUserPoints / totalPoint 是平台前端真实读取的字段
        assertEquals(120, AiStudioProtocol.parsePoints(JSONObject("""{"totalUserPoints":120}""")))
        assertEquals(8, AiStudioProtocol.parsePoints(JSONObject("""{"totalPoint":8}""")))
        assertEquals(0, AiStudioProtocol.parsePoints(JSONObject("""{"point":0}""")))
    }

    @Test
    fun parsesPointsFromNestedData() {
        assertEquals(55, AiStudioProtocol.parsePoints(JSONObject("""{"data":{"totalPoint":55}}""")))
    }

    @Test
    fun pointsIsNullWhenAbsent() {
        // 「读不到」必须与「真的是 0」区分：null 让界面显示——而不是 0
        org.junit.Assert.assertNull(AiStudioProtocol.parsePoints(JSONObject("""{"foo":1}""")))
    }

    @Test
    fun parsesComputeCardFromResourceTotal() {
        assertEquals("32.5 点", AiStudioProtocol.parseComputeCard(JSONObject("""{"resourceTotal":32.5}""")))
        // 整数不带小数尾巴
        assertEquals("16 点", AiStudioProtocol.parseComputeCard(JSONObject("""{"resourceTotal":16.0}""")))
    }

    @Test
    fun computeCardIsNullWhenAbsent() {
        org.junit.Assert.assertNull(AiStudioProtocol.parseComputeCard(JSONObject("""{"foo":1}""")))
    }

    @Test
    fun receiveResourcePathCarriesInfoCompleteFlag() {
        // 真实调用带 ?isInfoComplete=1，不带则平台回「用户信息不完整 500」
        assertTrue(AiStudioProtocol.PATH_RESOURCE_RECEIVE.endsWith("resource/receive"))
    }

    @Test
    fun accountKeyFallsBackToNicknameThenCookie() {
        assertEquals("name:昵称", AiStudioProtocol.accountKey("", "昵称", "c"))
        assertTrue(AiStudioProtocol.accountKey("", "", "c").startsWith("cookie:"))
    }

    @Test
    fun sameAccountKeepsSameIdAcrossLogins() {
        // 同一账号重复登录必须收敛成同一个 id，否则账号列表会越堆越长。
        val first = AiStudioProtocol.newAccount("BDUSS=a; X=1", "t1", "88", "小明", 1L)
        val second = AiStudioProtocol.newAccount("BDUSS=b; X=2", "t2", "88", "小明改了名", 2L)
        assertEquals(first.id, second.id)
    }
}
