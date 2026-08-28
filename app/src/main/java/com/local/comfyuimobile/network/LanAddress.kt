package com.local.comfyuimobile.network

import java.net.URI

object LanAddress {
    // 未显式写端口时：本地/局域网沿用 ComfyUI 默认 8188，HTTPS 走标准 443。
    private const val DEFAULT_HTTP_PORT = 8188
    private const val DEFAULT_HTTPS_PORT = 443

    fun normalize(input: String): String {
        // 用户常从云平台控制台复制地址，粘贴内容会带上换行和说明文字（例如 CloudStudio
        // 会在域名后另起一行 "cloudstudio.net"），直接解析会在 authority 段撞上换行符，
        // 抛 "Illegal character in authority"。这里只取第一行再 trim；刻意不动行内空格，
        // 保持"含空格属于畸形地址"的既有校验语义。
        val firstLine = input.lineSequence().firstOrNull().orEmpty().trim()
        require(firstLine.isNotEmpty()) { "请输入 ComfyUI 地址" }
        val withScheme = if (firstLine.contains("://")) firstLine else "http://$firstLine"
        val uri = runCatching { URI(withScheme) }.getOrElse {
            throw IllegalArgumentException("地址格式不正确，请只粘贴连接地址，不要带多余文字或换行")
        }
        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("地址格式不正确")
        require(scheme == "http" || scheme == "https") { "只支持 HTTP 或 HTTPS 地址" }
        val host = uri.host ?: throw IllegalArgumentException("地址格式不正确")
        require(host.isNotBlank()) { "地址格式不正确" }
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> DEFAULT_HTTPS_PORT
            else -> DEFAULT_HTTP_PORT
        }
        require(port in 1..65535) { "端口无效" }
        val formattedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        // 保留 user:pass@ 片段：OkHttp 会自动把它转成 Basic 认证头，用于穿透
        // 云端常见的反向代理登录；同时凭据随服务器配置一起持久化，重启后不丢。
        val userInfo = uri.rawUserInfo?.takeIf { it.isNotBlank() }?.let { "$it@" }.orEmpty()
        // 保留子路径：云端常把 ComfyUI 挂在反向代理的子路径下（如 https://host/comfyui），
        // 丢掉它会导致所有请求打到根路径而 404。查询串和锚点对 base URL 没有意义，丢弃。
        val path = uri.rawPath?.trimEnd('/')?.takeIf { it.isNotBlank() }.orEmpty()
        return "$scheme://$userInfo$formattedHost:$port$path"
    }

    /** 去掉认证信息后的纯地址，用于展示和 WebView 加载。 */
    fun withoutCredentials(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val scheme = url.substring(0, schemeEnd + 3)
        val rest = url.substring(schemeEnd + 3)
        // 凭据只可能出现在 authority 段（到第一个 / ? # 为止）。若直接对整个 URL 找 '@'，
        // 像 http://host:8188/path?x=a@b 这种地址会被错误截断。
        val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#'))
        val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
        val tail = if (authorityEnd < 0) "" else rest.substring(authorityEnd)
        val at = authority.lastIndexOf('@')
        if (at < 0) return url
        return scheme + authority.substring(at + 1) + tail
    }

    /** 去掉认证信息后的纯主机名，用于界面展示。 */
    fun displayHost(url: String): String {
        val cleaned = withoutCredentials(url)
        val withoutScheme = cleaned.substringAfter("://", cleaned)
        val authority = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        // IPv6 地址整体包在方括号里，地址内部的冒号不是端口分隔符，必须先按方括号截断，
        // 否则 [::1]:8188 会被切成 "[" 。
        if (authority.startsWith('[')) {
            val end = authority.indexOf(']')
            if (end > 0) return authority.substring(0, end + 1)
        }
        return authority.substringBefore(':')
    }

    /**
     * 判断目标地址与基准地址是否同源（scheme + host + port 三者一致）。
     * 不能用 startsWith 做前缀匹配：http://host:8188.attacker.tld 会以
     * http://host:8188 开头，从而骗过校验被放进特权 WebView。
     */
    fun isSameOrigin(base: String, target: String): Boolean {
        val a = runCatching { URI(base) }.getOrNull() ?: return false
        val b = runCatching { URI(target) }.getOrNull() ?: return false
        val schemeA = a.scheme?.lowercase() ?: return false
        val schemeB = b.scheme?.lowercase() ?: return false
        if (schemeA != schemeB) return false
        val hostA = a.host?.lowercase() ?: return false
        val hostB = b.host?.lowercase() ?: return false
        if (hostA != hostB) return false
        val portA = if (a.port != -1) a.port else defaultPortFor(schemeA)
        val portB = if (b.port != -1) b.port else defaultPortFor(schemeB)
        return portA == portB
    }

    private fun defaultPortFor(scheme: String): Int =
        if (scheme == "https") DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT

    fun isTrustedHost(host: String): Boolean {
        val trimmed = host.trim()
        if (trimmed.isBlank()) return false
        return runCatching { URI("http://$trimmed").host != null }.getOrDefault(false)
    }

    /**
     * 取出地址里的反向代理登录凭据（形如 https://user:pass@host:port）。
     * 密码里的 @ : / 等字符需要先做 URL 编码，否则解析会歧义。
     */
    fun credentials(input: String): Pair<String, String>? {
        // 用 userInfo（已解码）而不是 rawUserInfo：%40 这类转义要还原成真实字符再送去认证，
        // 否则服务器收到的会是编码后的字面量。
        val raw = runCatching {
            val trimmed = input.trim()
            val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
            URI(withScheme).userInfo
        }.getOrNull()
        if (raw.isNullOrBlank()) return null
        val separator = raw.indexOf(':')
        if (separator <= 0 || separator == raw.length - 1) return null
        return raw.substring(0, separator) to raw.substring(separator + 1)
    }

    fun subnet24(address: String): List<String> {
        val parts = address.split('.')
        require(parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 })
        val prefix = parts.take(3).joinToString(".")
        return (1..254).map { "$prefix.$it" }
    }
}
