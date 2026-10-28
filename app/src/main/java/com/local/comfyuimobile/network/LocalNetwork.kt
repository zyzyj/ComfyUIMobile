package com.local.comfyuimobile.network

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 取本机局域网 IPv4 地址（v0.3.15）。
 *
 * 服务于 MCP 的「局域网访问」：服务要绑 `0.0.0.0` 才能被同一 WiFi 下的其它设备连上，
 * 而给客户端的 URL 与通知栏文案都必须用**本机实际 IP**，不是 127.0.0.1（那会让
 * 电脑去连它自己）。
 *
 * 刻意**不放进 [LanAddress]**：那个 object 是 URL 归一化/同源比较工具，语义与
 * 这里完全不同。硬塞进去会让「取本机 IP」和「比较两台服务器是否同一台」混在
 * 一个类里，正是本项目反复踩的平行路径。
 */
object LocalNetwork {

    /**
     * 候选的本机 IPv4 地址（按可连性排序，已去重、排除虚拟/回环）。
     *
     * 为什么返回**多个**而不是挑一个：
     * 一台设备常见同时有 WiFi、蜂窝、VPN、热点等多个接口，哪个能连上对端设备
     * 取决于网络拓扑——本机无法可靠判断。把候选都列出来让用户选，比「猜一个
     * 大概率错的」安全得多（挑错是局域网里最难排查的失败）。
     *
     * 排序规则（仅影响展示顺序，不代表可用性保证）：
     *  1. 192.168.x.x（家庭/公司 WiFi 最常见）
     *  2. 10.x.x.x（企业网/VPN）
     *  3. 172.16~31.x.x（Docker/企业网）
     *  4. 其它私有地址
     * 公网地址不返回——本服务绝不该监听在公网出口上。
     *
     * 排除：回环（127.x）、链路本地（169.254.x，没网）、组播、`docker*`/`veth*`/
     * `br-*` 等虚拟网卡名。`isUp` 为 false 的接口直接跳过。
     */
    fun ipv4Candidates(): List<String> {
        val seen = LinkedHashMap<String, Int>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return emptyList()
        for (nif in interfaces) {
            if (!nif.isUp) continue
            val name = nif.name.lowercase()
            if (name.startsWith("docker") || name.startsWith("veth") ||
                name.startsWith("br-") || name.startsWith("virbr") ||
                name.startsWith("tun") || name.startsWith("tap") ||
                name.startsWith("lo")
            ) continue
            for (address in nif.inetAddresses) {
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress ||
                    address.isMulticastAddress || address.isAnyLocalAddress
                ) continue
                val host = address.hostAddress ?: continue
                if (!isPrivateIpv4(host)) continue
                // 同一 IP 挂在多个接口上只保留第一次。
                if (seen.containsKey(host)) continue
                seen[host] = priority(host)
            }
        }
        return seen.entries.sortedBy { it.value }.map { it.key }
    }

    /** 首个候选；没有则返回 null。用于「只有一个网卡」的常见场景。 */
    fun primaryIpv4(): String? = ipv4Candidates().firstOrNull()

    /** 私有 IPv4 段判定（RFC 1918）。 */
    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return when {
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            else -> false
        }
    }

    /** 展示排序优先级：越小越靠前。 */
    private fun priority(host: String): Int {
        val parts = host.split('.')
        val a = parts[0].toIntOrNull() ?: return Int.MAX_VALUE
        val b = parts[1].toIntOrNull() ?: return Int.MAX_VALUE
        return when {
            a == 192 && b == 168 -> 0
            a == 10 -> 1
            a == 172 && b in 16..31 -> 2
            else -> 3
        }
    }
}
