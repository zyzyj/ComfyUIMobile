package com.local.comfyuimobile.network

import org.junit.Assert.assertEquals
import org.junit.Test

class UserdataPathTest {
    @Test fun encodesChineseAndSpacesPerPathSegment() {
        assertEquals(
            "%E5%B7%A5%E4%BD%9C%E6%B5%81%2F%E6%88%91%E7%9A%84%20Krea2.json",
            UserdataPath.encode("工作流/我的 Krea2.json"),
        )
    }

    @Test fun acceptsWindowsSeparatorsWithoutEncodingSlash() {
        assertEquals("folder%2Fchild.json", UserdataPath.encode("folder\\child.json"))
    }

    @Test fun defaultModeKeepsSingleEncoding() {
        // 默认（直连 ComfyUI）必须保持原来的单次编码，别让正常服务器收到带百分号的怪文件名。
        assertEquals(
            "workflows%2F%E4%BB%BB%E5%8A%A1.json",
            UserdataPath.encode("workflows/任务.json"),
        )
    }

    @Test fun doubleEncodeWrapsTheWholeSegmentOneMoreTime() {
        // AI Studio 的代理会解掉一层编码再转发：`%` → `%25` 之后，代理解一层
        // 剩下的正好是单次编码，aiohttp 再解一次才还原成中文。
        val double = UserdataPath.encode("workflows/任务.json", doubleEncode = true)
        assertEquals("workflows%252F%25E4%25BB%25BB%25E5%258A%25A1.json", double)
        // 解一次 = 单次编码；解两次 = 原文（等价于 URLDecoder 套两层）。
        assertEquals(
            UserdataPath.encode("workflows/任务.json"),
            java.net.URLDecoder.decode(double, Charsets.UTF_8.name()),
        )
    }

    @Test fun doubleEncodeOfAsciiPathIsHarmless() {
        // 纯 ASCII 路径走双重编码也不该炸：只是每个 % 变 %%。
        assertEquals(
            java.net.URLDecoder.decode(UserdataPath.encode("workflows/a b.json", doubleEncode = true), Charsets.UTF_8.name()),
            UserdataPath.encode("workflows/a b.json"),
        )
    }
}
