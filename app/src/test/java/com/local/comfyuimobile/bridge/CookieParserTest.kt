package com.local.comfyuimobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieParserTest {

    /** 用户真实场景：AI Studio 一整串 Cookie，含引号值、畸形段、重复项。 */
    private val realAiStudioCookie =
        "user-20103961-10660803=2|1:0|10:1787982075|22:user-20103961-10660803|40:N1Z5ZFdoU25Lb0tFQzFCUjVLMXlxUjhhbktvNXVT|fd8c1023fa74fff17677ba876d991003be083ff99c323a8413828bee90e8d32c; " +
            "BAIDUID_BFESS=1BB24E9AB945F702923D7B229A310561:FG=1; " +
            "Hm_lvt_be6b0f3e9ab579df8f47db4641a0a406=1787912708; " +
            "jsdk-uuid=16ed4321-2430-4fc9-99b3-84d443fddae1; " +
            "undefined; " +
            "JSESSIONID=7E22D1ADAC054C57120F17DC44044834; " +
            "ide-proxy=eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJ1c2VyX2lkIjoiMjAxMDM5NjEifQ.NK2_bNWEKV19546lBjHd2K3i3gZ-bJNxmFGt5XEYs9A; " +
            "RT=\"z=1&dm=baidu.com&si=abe1dada-a159-4eb1-95fe-636524e57e65\"; " +
            "BDUSS=ktMM0JsVnBiN044RGZnOW5xbVQtcDNxMnZoVXJQcDU0cTBUSFRqSm5qUjNNcmxxSVFBQUFBJCQ"

    @Test
    fun parsesRealAiStudioCookieIntoSegments() {
        val segments = CookieParser.parse(realAiStudioCookie)

        // 关键两段必须保留
        assertTrue(segments.any { it.startsWith("user-20103961-10660803=") })
        assertTrue(segments.any { it.startsWith("ide-proxy=") })
        assertTrue(segments.any { it.startsWith("BDUSS=") })

        // 畸形段 undefined 被丢弃
        assertTrue(segments.none { it.startsWith("undefined") })

        // 带引号的值被剥掉引号
        val rt = segments.firstOrNull { it.startsWith("RT=") }
        assertEquals("RT=z=1&dm=baidu.com&si=abe1dada-a159-4eb1-95fe-636524e57e65", rt)

        // 每段都是合法 name=value
        segments.forEach { seg ->
            val eq = seg.indexOf('=')
            assertTrue("段应有 = : $seg", eq > 0)
            assertTrue("name 非空 : $seg", seg.substring(0, eq).isNotBlank())
            assertTrue("value 非空 : $seg", seg.substring(eq + 1).isNotBlank())
        }
    }

    @Test
    fun emptyOrBlankCookieReturnsEmptyList() {
        assertTrue(CookieParser.parse("").isEmpty())
        assertTrue(CookieParser.parse("   ").isEmpty())
        assertTrue(CookieParser.parse("; ; ;").isEmpty())
    }

    @Test
    fun simpleCookieParsesCorrectly() {
        val segments = CookieParser.parse("a=1; b=2")
        assertEquals(listOf("a=1", "b=2"), segments)
    }

    @Test
    fun skipsSegmentsWithoutEquals() {
        val segments = CookieParser.parse("a=1; garbage; b=2; =novalue; c=")
        assertEquals(listOf("a=1", "b=2"), segments)
    }

    @Test
    fun handlesWhitespaceAndSemicolons() {
        val segments = CookieParser.parse("  a = 1  ;  b  = 2 ;c=3  ")
        assertEquals(listOf("a=1", "b=2", "c=3"), segments)
    }
}
