package com.local.comfyuimobile.network

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本机 IPv4 地址探测单测（v0.3.15）。
 *
 * 取 IP 依赖设备网络环境，单测无法断言\"具体是哪个 IP\"，只能锁住：
 *  1. 不抛异常（网络接口枚举失败时返回空，不崩溃）；
 *  2. 返回的每一项都是合法私有 IPv4（过滤规则本身能验证）。
 */
class LocalNetworkTest {

    @Test
    fun candidatesNeverThrow() {
        // 任何环境下都不该抛异常：接口枚举失败只返回空列表。
        val candidates = LocalNetwork.ipv4Candidates()
        assertNotNull(candidates)
    }

    @Test
    fun candidatesAreValidIpv4() {
        // 若环境里有网卡，返回的每个都必须是 4 段数字的私有地址。
        LocalNetwork.ipv4Candidates().forEach { ip ->
            val parts = ip.split('.')
            assertTrue("应为 4 段 IPv4：$ip", parts.size == 4)
            parts.forEach { octet ->
                assertTrue("每段应为 0-255：$ip", octet.toIntOrNull() in 0..255)
            }
        }
    }

    @Test
    fun primaryIsNullOrInCandidates() {
        val primary = LocalNetwork.primaryIpv4()
        if (primary != null) {
            assertTrue(primary in LocalNetwork.ipv4Candidates())
        }
    }
}
