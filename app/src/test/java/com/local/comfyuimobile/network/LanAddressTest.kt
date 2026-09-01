package com.local.comfyuimobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressTest {
    @Test fun normalizesPrivateAddressAndDefaultPort() {
        assertEquals("http://192.168.10.109:8188", LanAddress.normalize("192.168.10.109/"))
        assertEquals("http://10.0.0.2:9000", LanAddress.normalize("http://10.0.0.2:9000"))
        assertEquals("http://100.64.0.10:18188", LanAddress.normalize("http://100.64.0.10:18188/"))
        assertEquals("http://8.8.8.8:8188", LanAddress.normalize("8.8.8.8"))
        assertEquals("http://comfy.example.com:18188", LanAddress.normalize("http://comfy.example.com:18188"))
    }

    @Test fun acceptsPrivateVpnSharedAndPublicRanges() {
        assertTrue(LanAddress.isTrustedHost("100.64.0.1"))
        assertTrue(LanAddress.isTrustedHost("198.18.0.1"))
        assertTrue(LanAddress.isTrustedHost("8.8.8.8"))
        assertTrue(LanAddress.isTrustedHost("vpn-comfy.example"))
    }

    @Test fun acceptsHttpsForRemoteServers() {
        assertEquals("https://192.168.1.2:8188", LanAddress.normalize("https://192.168.1.2:8188"))
        assertEquals("https://comfy.example.com:443", LanAddress.normalize("https://comfy.example.com"))
        assertEquals("https://comfy.example.com:18188", LanAddress.normalize("https://comfy.example.com:18188/"))
        assertEquals("https://8.8.8.8:443", LanAddress.normalize("HTTPS://8.8.8.8"))
    }

    @Test fun rejectsUnsupportedSchemesAndMalformedAddresses() {
        listOf("ftp://192.168.1.2:8188", "http://", "not a valid host").forEach { value ->
            assertTrue(runCatching { LanAddress.normalize(value) }.isFailure)
        }
        assertFalse(LanAddress.isTrustedHost(""))
    }

    @Test fun keepsReverseProxyCredentials() {
        assertEquals(
            "https://user:pass@comfy.example.com:443",
            LanAddress.normalize("https://user:pass@comfy.example.com"),
        )
        assertEquals("user" to "pass", LanAddress.credentials("https://user:pass@comfy.example.com"))
        assertEquals("user" to "p@ss", LanAddress.credentials("https://user:p%40ss@comfy.example.com"))
    }

    @Test fun stripsCredentialsForDisplayAndWebView() {
        val withCredentials = "https://user:pass@comfy.example.com:8443"
        assertEquals("https://comfy.example.com:8443", LanAddress.withoutCredentials(withCredentials))
        assertEquals("comfy.example.com", LanAddress.displayHost(withCredentials))
        // 没有凭据时保持原样，不影响现有的本地地址。
        assertEquals("http://192.168.10.109:8188", LanAddress.withoutCredentials("http://192.168.10.109:8188"))
        assertEquals("192.168.10.109", LanAddress.displayHost("http://192.168.10.109:8188"))
    }

    @Test fun keepsSubPathForReverseProxiedServers() {
        assertEquals("https://comfy.example.com:443/comfyui", LanAddress.normalize("https://comfy.example.com/comfyui"))
        assertEquals("https://comfy.example.com:443/comfyui", LanAddress.normalize("https://comfy.example.com/comfyui/"))
        // 查询串与锚点对 base URL 没有意义，应当丢弃。
        assertEquals("http://192.168.1.10:8188", LanAddress.normalize("http://192.168.1.10:8188/?foo=bar#x"))
        // 归一化必须幂等。
        val once = LanAddress.normalize("https://user:pass@comfy.example.com/comfyui")
        assertEquals(once, LanAddress.normalize(once))
    }

    @Test fun doesNotMangleUrlContainingAtSign() {
        // 凭据只可能出现在 authority 段，路径/查询里的 @ 不能被当成凭据分隔符。
        assertEquals("http://host:8188/path?x=a@b", LanAddress.withoutCredentials("http://host:8188/path?x=a@b"))
        assertEquals("host", LanAddress.displayHost("http://host:8188/path?x=a@b"))
    }

    @Test fun comparesOriginBySchemeHostAndPort() {
        val base = "http://192.168.1.10:8188"
        assertTrue(LanAddress.isSameOrigin(base, "http://192.168.1.10:8188/any/path"))
        assertTrue(LanAddress.isSameOrigin("https://comfy.example.com:443", "https://comfy.example.com/"))
        // 前缀相同但主机不同，必须判为不同源（startsWith 会在这里被骗过）。
        assertFalse(LanAddress.isSameOrigin(base, "http://192.168.1.10:8188.attacker.tld/x"))
        assertFalse(LanAddress.isSameOrigin(base, "http://192.168.1.11:8188/"))
        assertFalse(LanAddress.isSameOrigin("https://a.com:443", "http://a.com:80/"))
        assertFalse(LanAddress.isSameOrigin(base, "about:blank"))
    }

    @Test fun handlesIpv6Hosts() {
        assertEquals("http://[::1]:8188", LanAddress.normalize("[::1]"))
        assertEquals("https://[2001:db8::1]:443", LanAddress.normalize("https://[2001:db8::1]"))
        // 地址内部的冒号不能被当成端口分隔符。
        assertEquals("[::1]", LanAddress.displayHost("http://[::1]:8188"))
        assertEquals("[2001:db8::1]", LanAddress.displayHost("https://user:pass@[2001:db8::1]:443"))
    }

    @Test fun cleansMultilinePastedAddress() {
        // 从云平台控制台复制的地址常带换行与说明文字，必须自动清洗而不是报错。
        assertEquals(
            "https://52e4812a33304229ab6ddc1d50865c1e--8188.ap-shanghai2.cloudstudio.club:443",
            LanAddress.normalize(
                "https://52e4812a33304229ab6ddc1d50865c1e--8188.ap-shanghai2.cloudstudio.club\ncloudstudio.net",
            ),
        )
        // \r\n 与首尾空白同样要容忍。
        assertEquals("http://192.168.10.109:8188", LanAddress.normalize("  192.168.10.109  \r\n "))
        assertEquals("http://comfy.example.com:8188", LanAddress.normalize("comfy.example.com\r\n随便写点什么"))
        // 纯空白输入仍然拒绝。
        assertTrue(runCatching { LanAddress.normalize("  \n  ") }.isFailure)
    }

    @Test fun createsCompleteSlash24Subnet() {
        val addresses = LanAddress.subnet24("192.168.7.20")
        assertEquals(254, addresses.size)
        assertEquals("192.168.7.1", addresses.first())
        assertEquals("192.168.7.254", addresses.last())
    }

    @Test fun bareHostNormalizesPortsAndBrackets() {
        // v0.1.71：Basic Auth 的 host 比对全靠它。两边必须收敛到同一个字符串，
        // 否则 IPv6 服务器永远匹配不上（老代码按 ':' 切，把 [::1]:8188 切成了 "["）。
        assertEquals("::1", LanAddress.bareHost("[::1]:8188"))
        assertEquals("::1", LanAddress.bareHost("[::1]"))
        assertEquals("::1", LanAddress.bareHost("::1"))
        assertEquals("::1", LanAddress.bareHost("http://[::1]:8188/prompt"))
        assertEquals("2001:db8::1", LanAddress.bareHost("https://user:pass@[2001:db8::1]:443"))
        assertEquals("comfy.example.com", LanAddress.bareHost("http://comfy.example.com:8188"))
        assertEquals("comfy.example.com", LanAddress.bareHost("comfy.example.com"))
        // 大小写与末尾的点不该影响匹配。
        assertEquals("comfy.example.com", LanAddress.bareHost("HTTP://COMFY.Example.COM.:8188/"))
        // 空输入不能崩。
        assertEquals("", LanAddress.bareHost(""))
    }
}
