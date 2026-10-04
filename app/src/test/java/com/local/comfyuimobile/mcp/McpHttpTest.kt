package com.local.comfyuimobile.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 手写 HTTP 解析的单测（v0.2.85）。
 *
 * 这套解析只覆盖本项目真正会收到的请求，边界也因此明确：拒绝 chunked、
 * 必须有 Content-Length、上限之外直接拒。这些"拒绝路径"都要有测试——
 * 漏判一个会变成解析半个 body 再报 JSON 错，把协议问题伪装成业务问题。
 */
class McpHttpTest {

    private fun parse(raw: String): HttpReadResult =
        McpHttp.readRequest(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))

    private fun ok(raw: String): HttpRequest {
        val result = parse(raw)
        assertTrue("期望解析成功，实际 $result", result is HttpReadResult.Ok)
        return (result as HttpReadResult.Ok).request
    }

    @Test
    fun parsesSimplePostRequest() {
        val body = """{"jsonrpc":"2.0"}"""
        val request = ok(
            "POST /mcp HTTP/1.1\r\n" +
                "Host: 127.0.0.1:8765\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "\r\n" +
                body,
        )
        assertEquals("POST", request.method)
        assertEquals("/mcp", request.path)
        assertEquals(body, request.body)
    }

    @Test
    fun readsBodyByteExactForMultibyteContent() {
        // 内容长度按**字节**算而非字符数。中文提示词一旦被按字符截断，
        // 服务端会用半个 UTF-8 序列解析 JSON 并失败。
        val body = """{"prompt":"一只猫"}"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val request = ok(
            "POST /mcp HTTP/1.1\r\nContent-Length: ${bytes.size}\r\n\r\n" + body,
        )
        assertEquals(body, request.body)
    }

    @Test
    fun headerNamesAreCaseInsensitive() {
        val request = ok(
            "POST /mcp HTTP/1.1\r\ncontent-length: 2\r\nX-Custom: v\r\n\r\n{}",
        )
        assertEquals("{}", request.body)
        assertEquals("v", request.headers["x-custom"])
    }

    @Test
    fun rejectsChunkedEncoding() {
        val result = parse("POST /mcp HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")
        assertEquals(HttpReadResult.Bad::class, result::class)
        assertEquals(411, (result as HttpReadResult.Bad).status)
    }

    @Test
    fun rejectsOverlongBody() {
        val result = parse(
            "POST /mcp HTTP/1.1\r\nContent-Length: ${McpHttp.MAX_BODY_BYTES + 1}\r\n\r\n",
        )
        assertEquals(413, (result as HttpReadResult.Bad).status)
    }

    @Test
    fun rejectsIncompleteBody() {
        // 声明 10 字节却只给 2 字节：必须报错，不能把残body当成完整请求。
        val result = parse("POST /mcp HTTP/1.1\r\nContent-Length: 10\r\n\r\n{}")
        assertTrue(result is HttpReadResult.Bad)
    }

    @Test
    fun rejectsBadRequestLine() {
        val result = parse("GARBAGE\r\n\r\n")
        assertTrue(result is HttpReadResult.Bad)
    }

    @Test
    fun parsesQueryString() {
        val request = ok("GET /files/a.png?x=1&y=2 HTTP/1.1\r\n\r\n")
        assertEquals("/files/a.png", request.path)
        assertEquals("1", request.query["x"])
        assertEquals("2", request.query["y"])
    }

    @Test
    fun responseAlwaysIncludesConnectionCloseAndLength() {
        val bytes = McpHttp.jsonResponse("""{"ok":true}""")
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("Connection: close\r\n"))
        assertTrue(text.contains("Content-Length: 11\r\n"))
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
    }

    @Test
    fun emptyResponseIsAcceptedWithZeroLength() {
        val text = String(McpHttp.emptyResponse(202), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("HTTP/1.1 202 Accepted\r\n"))
        assertTrue(text.contains("Content-Length: 0\r\n"))
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun bytesResponseKeepsBinaryPayload() {
        val payload = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0xFF.toByte())
        val bytes = McpHttp.bytesResponse(payload, "image/png")
        val separator = "\r\n\r\n"
        val head = String(bytes, Charsets.ISO_8859_1)
        val bodyStart = head.indexOf(separator) + separator.length
        val body = bytes.copyOfRange(bodyStart, bytes.size)
        assertEquals(payload.toList(), body.toList())
    }
}
