package com.local.comfyuimobile.mcp

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * MCP server 的 HTTP 层（v0.2.85）。
 *
 * 手写而非引入框架：端点只有两个（`POST /mcp`、`GET /files/{id}`），请求体都是小 JSON。
 * 拆成纯函数后可脱离 Android 单测——这是本项目攒下测试的既有方式（见 `CommandAllowlist`）。
 *
 * 解析面刻意压到最小（规划书 §3.4）：
 *  - 只接受 `Content-Length`；收到 `Transfer-Encoding: chunked` 直接拒（411）。
 *    实测 AiCode 走 OkHttp `String.toRequestBody()`，一定带 Content-Length，不走 chunked。
 *  - 响应一律 `Connection: close`，不做 keep-alive——一问一答，每次新建连接。
 *
 * 上限存在的意义不是"优雅处理"，而是**不让对端用一个超长头把内存撑爆**：超过即断开。
 */
internal object McpHttp {

    /** 请求头上限。正常头不到 1KB，16KB 足够宽松。 */
    const val MAX_HEADER_BYTES = 16 * 1024

    /** 请求体上限。MCP 请求都是小 JSON；1MB 足够容纳最长的 tools/call 参数。 */
    const val MAX_BODY_BYTES = 1024 * 1024

    fun readRequest(input: InputStream): HttpReadResult {
        val headerBytes = readHeaderBlock(input) ?: return HttpReadResult.Bad(400, "无法读取请求头")
        // 头部按 ISO-8859-1 解：这是 HTTP 头的规定编码，逐字节保真，不会因 UTF-8 解码失败。
        val headerText = String(headerBytes, Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        val requestLine = lines.firstOrNull() ?: return HttpReadResult.Bad(400, "缺少请求行")
        val parts = requestLine.split(' ')
        if (parts.size < 3) return HttpReadResult.Bad(400, "请求行格式错误")
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        // chunked 一律拒绝：手写解析不支持分块，静默当成长度 0 会读到半个 body 再当成
        // "JSON 解析失败"，把一个明确的协议不支持伪装成业务错误，排查时会走很远弯路。
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return HttpReadResult.Bad(411, "不支持 Transfer-Encoding: chunked，请带 Content-Length")
        }

        val length = headers["content-length"]?.trim()?.toIntOrNull() ?: 0
        if (length < 0) return HttpReadResult.Bad(400, "Content-Length 非法")
        if (length > MAX_BODY_BYTES) return HttpReadResult.Bad(413, "请求体过大")

        val body = if (length > 0) {
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) return HttpReadResult.Bad(400, "请求体不完整")
                read += n
            }
            String(buf, Charsets.UTF_8)
        } else {
            ""
        }

        return HttpReadResult.Ok(HttpRequest(method, target, headers, body))
    }

    /**
     * 读到空行（`\r\n\r\n`）为止，返回含两个空行的原始头字节。
     *
     * 用字节而非按行读：先按 `readLine` 会丢掉原始的 CRLF 边界，且遇到不含换行的
     * 超长头会一直读到 OOM——这里在 [MAX_HEADER_BYTES] 处主动放弃。
     */
    private fun readHeaderBlock(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (out.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            matched = when {
                b == CR && (matched == 0 || matched == 2) -> matched + 1
                b == LF && (matched == 1 || matched == 3) -> matched + 1
                b == CR -> 1
                else -> 0
            }
            if (matched == 4) return out.toByteArray()
        }
        return null
    }

    private const val CR = 13
    private const val LF = 10

    /** 组一个 `Connection: close` 的响应。所有响应都经这里，避免各处漏写长度。 */
    fun response(
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ): ByteArray {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        head.append("Content-Type: ").append(contentType).append("\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        for ((name, value) in extraHeaders) head.append(name).append(": ").append(value).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        val headBytes = head.toString().toByteArray(Charsets.ISO_8859_1)
        return headBytes + body
    }

    fun jsonResponse(
        body: String,
        status: Int = 200,
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ): ByteArray = response(
        status,
        reasonFor(status),
        "application/json",
        body.toByteArray(Charsets.UTF_8),
        extraHeaders,
    )

    /** 通知的应答：空体 + 202。AiCode 按 spec 期望这个形态（`StreamableHttpTransport`）。 */
    fun emptyResponse(status: Int = 202): ByteArray = response(
        status,
        reasonFor(status),
        "text/plain",
        ByteArray(0),
    )

    /**
     * 只出头部，body 由调用方流式写入。
     *
     * Content-Length 必须**事先知道**（这里用文件长度），不能用 chunked——
     * 本项目刻意不做分块，且对端的 OkHttp 读单条响应也要靠它定边界。
     */
    fun fileHeader(contentLength: Long, contentType: String, status: Int = 200): ByteArray {
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(status).append(' ').append(reasonFor(status)).append("\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n")
            .append("Content-Length: ").append(contentLength).append("\r\n")
            .append("Connection: close\r\n\r\n")
        return head.toString().toByteArray(Charsets.ISO_8859_1)
    }

    fun reasonFor(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        505 -> "HTTP Version Not Supported"
        else -> "OK"
    }
}

internal data class HttpRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: String,
) {
    /** 去掉查询串的路径部分。 */
    val path: String get() = target.substringBefore('?')

    val query: Map<String, String>
        get() = target.substringAfter('?', "")
            .split('&')
            .mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val eq = pair.indexOf('=')
                if (eq < 0) pair to "" else pair.substring(0, eq) to pair.substring(eq + 1)
            }
            .toMap()
}

internal sealed interface HttpReadResult {
    data class Ok(val request: HttpRequest) : HttpReadResult

    /** 无法解析的请求：直接以该状态码回绝，不进入业务逻辑。 */
    data class Bad(val status: Int, val reason: String) : HttpReadResult
}
