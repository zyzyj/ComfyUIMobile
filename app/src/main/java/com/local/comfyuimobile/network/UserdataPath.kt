package com.local.comfyuimobile.network

import java.net.URLEncoder

object UserdataPath {
    /**
     * 把工作流路径编码成可放进 URL 的形式。
     *
     * @param doubleEncode 再编一层。某些反向代理（百度 AI Studio）转发时会把路径解掉
     *   一层编码，中文文件名于是变成裸的非 ASCII 字节，被后端 HTTP 服务器以
     *   `Invalid char in url path`（HTTP 400）拒掉。把 `%` 再编成 `%25`，代理解掉
     *   一层后到 ComfyUI 时正好是单次编码，解出来还是原来的中文。
     *   只对实测出问题的服务器开，默认关——直连 ComfyUI 用了反而会生成带百分号的怪文件名。
     */
    fun encode(path: String, doubleEncode: Boolean = false): String {
        val once = URLEncoder
            .encode(path.replace('\\', '/').trim('/'), Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%7E", "~", ignoreCase = true)
        if (!doubleEncode) return once
        return URLEncoder.encode(once, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
