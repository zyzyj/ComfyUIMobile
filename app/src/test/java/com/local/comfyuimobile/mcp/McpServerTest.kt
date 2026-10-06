package com.local.comfyuimobile.mcp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * MCP server 的**真实 socket 端到端**测试（v0.2.85）。
 *
 * 起一个真的 `ServerSocket`、用真的 HTTP 客户端连它。这比"解析函数各自正确"更有价值：
 * 上一批 bug 里有一类正是"两个函数各自正确、串起来坏"（组合失效），只有端到端能抓到。
 *
 * 假 host 提供确定性结果，不碰网络与 ComfyUI。
 */
class McpServerTest {

    /**
     * 每个测试的硬上限（JUnit 层）。
     *
     * 本类用真实 socket，任何一处阻塞（连接未响应、服务端未回）都会让 Gradle 一直等，
     * 而 job 上限是 40 分钟——本套测试首次跑就把 CI 拖到了超时被取消。除了给各种
     * HTTP 连接设超时，这里再加一道进程级保险。
     */
    @Rule
    @JvmField
    val globalTimeout: Timeout = Timeout.seconds(20)

    private class FakeHost : McpToolHost {
        var lastRequest: GenerateRequest? = null

        override suspend fun listModels(type: String?): String =
            if (type == "lora") "loras:\n  a.safetensors" else "checkpoints:\n  sd_xl.safetensors"

        override suspend fun listWorkflows(): String = "demo  (workflows/demo.json)"

        override suspend fun generate(request: GenerateRequest, awaitMillis: Long): GenerateOutcome {
            lastRequest = request
            if (request.prompt == "fail") return GenerateOutcome.Failed("假失败")
            if (request.prompt == "slow") return GenerateOutcome.Running("job-run", 120, "仍在跑")
            return GenerateOutcome.Done(
                "job-1",
                listOf(
                    McpMedia(
                        filename = "out.png",
                        extension = "png",
                        contentType = "image/png",
                        file = tempPng(byteArrayOf(1, 2, 3, 4)),
                    ),
                ),
            )
        }

        /** 批量查询：这里按 id 后缀给出不同阶段，便于断言 queued/running/done。 */
        override suspend fun jobStatusBatch(ids: List<String>): String = ids.joinToString("\n") { id ->
            when {
                id.endsWith("q") -> "$id  queued · 位置 2 · 排队中，前面还有 1 个"
                id.endsWith("r") -> "$id  running · 位置 1 · 执行中"
                else -> "$id  done · 已完成"
            }
        }

        override suspend fun describeWorkflow(workflow: String?): String =
            "工作流：demo.json（workflows/demo.json）\n" +
                "可调字段 2 项，用 generate 的 params 传入 key 即可修改：\n" +
                "  3::steps  [steps]  Steps  当前=20  范围：1.0 ~ 100.0"

        override suspend fun cancelJobs(jobId: String?, includeOthers: Boolean): String =
            when {
                jobId != null -> "已请求中止 $jobId"
                includeOthers -> "已请求取消全部任务（含非本 App 提交的）"
                else -> "已请求取消本 App 提交的任务；另有 2 个非本 App 提交的任务保留未动"
            }

        override suspend fun jobStatus(jobId: String): GenerateOutcome = GenerateOutcome.Done(
            jobId,
            listOf(
                McpMedia(
                    filename = "done.png",
                    extension = "png",
                    contentType = "image/png",
                    file = tempPng(byteArrayOf(9)),
                ),
            ),
        )

        /** 真实实现是下载到磁盘的，这里也用真文件，才能测到 /files 的流式发送。 */
        private fun tempPng(bytes: ByteArray): java.io.File {
            val dir = java.io.File(System.getProperty("java.io.tmpdir"), "mcp-host-test")
            dir.mkdirs()
            return java.io.File.createTempFile("out", ".png", dir).apply { writeBytes(bytes) }
        }
    }

    private class Fixture : java.io.Closeable {
        val host = FakeHost()
        val files = McpFileStore(cacheDir = java.io.File(System.getProperty("java.io.tmpdir"), "mcp-test-${System.nanoTime()}"))
        private val portHolder = intArrayOf(-1)
        val server = McpServer(
            port = 0,
            token = TOKEN,
            tools = McpToolRegistry(host, files) { "http://127.0.0.1:${portHolder[0]}/files/" },
            // 必须与 registry 共用同一实例：各建一份的话，工具登记的文件在文件端点里
            // 根本查不到（这条曾经真的错过，由本测试拦住）。
            files = files,
        )

        fun start(): Int {
            server.start()
            portHolder[0] = server.boundPort
            return server.boundPort
        }

        override fun close() {
            runCatching { server.stop() }
            runCatching { files.clear() }
        }
    }

    private fun withServer(block: (Int, Fixture) -> Unit) {
        val fixture = Fixture()
        val port = fixture.start()
        try {
            block(port, fixture)
        } finally {
            fixture.close()
        }
    }

    /**
     * 重启后必须还能处理请求（P0-1 回归）。
     *
     * 曾经的 bug：`workers` 是 val、`stop()` 里 shutdownNow 后再不重建，于是第二次
     * start 能绑上端口、accept 线程也起得来，但第一个连接进来就抛
     * RejectedExecutionException、accept 线程当场死掉——界面显示"已启动"却一个请求
     * 都处理不了。「重新生成令牌」正是 stop → start，几乎必踩。
     *
     * 断言必须落到**真的发一个请求并拿到响应**，只查 isRunning 是抓不到的。
     */
    @Test
    fun restartAfterStopStillServesRequests() {
        val fixture = Fixture()
        try {
            val firstPort = fixture.start()
            assertEquals("首次启动应能处理请求", 200, postMcp(firstPort, """{"jsonrpc":"2.0","id":1,"method":"ping"}""").first)

            fixture.server.stop()

            val secondPort = fixture.start()
            val (code, body) = postMcp(secondPort, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            assertEquals("重启后必须仍能处理请求", 200, code)
            val tools = JSONObject(body).getJSONObject("result").getJSONArray("tools")
            assertTrue("重启后应能列出工具", tools.length() > 0)
        } finally {
            fixture.close()
        }
    }

    /** 发一个 MCP 请求，返回 (HTTP 状态码, 响应体)。 */
    private fun postMcp(port: Int, body: String, token: String = TOKEN): Pair<Int, String> {
        val payload = body.toByteArray()
        val connection = (URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.doOutput = true
        // 超时是硬件约束：这三个测试用真实 socket，一旦服务端不回，默认会无限等，
        // 而 Gradle 的 job 超时是 40 分钟——本套测试第一次跑就因此把 CI 拖到超时。
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        // 固定长度：与 AiCode 的 OkHttp （`String.toRequestBody`）一致，避免
        // HttpURLConnection 对大 body 自动转 chunked——那会撞上我们刻意拒绝的路径。
        connection.setFixedLengthStreamingMode(payload.size)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json, text/event-stream")
        if (token.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $token")
        connection.outputStream.use { it.write(payload) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return code to text
    }

    private fun get(port: Int, path: String): Triple<Int, ByteArray, String> {
        val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection)
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        val code = connection.responseCode
        val contentType = connection.contentType.orEmpty()
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        } ?: ByteArray(0)
        connection.disconnect()
        return Triple(code, bytes, contentType)
    }

    // ===== 握手 =====

    @Test
    fun completesHandshakeSequence() = withServer { port, fixture ->
        val (initCode, initBody) = postMcp(
            port,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"ai-code-editor","version":"1.0.0"}}}""",
        )
        assertEquals(200, initCode)
        val init = JSONObject(initBody).getJSONObject("result")
        assertEquals("2025-06-18", init.getString("protocolVersion"))
        assertEquals("comfy", init.getJSONObject("serverInfo").getString("name"))

        // 通知：无 id，回 202 且空体（AiCode 按 spec 期望这个形态）。
        val (notifyCode, notifyBody) = postMcp(
            port,
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
        )
        assertEquals(202, notifyCode)
        assertEquals("", notifyBody)

        val (listCode, listBody) = postMcp(port, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        assertEquals(200, listCode)
        val tools = JSONObject(listBody).getJSONObject("result").getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        // 不断言"恰好这 4 个"：加新工具时这种断言会变成假失败，把有意义的信号淹掉。
        // 改为断言核心工具都在，且全部满足命名约束。
        assertTrue("list_models 应在列", "list_models" in names)
        assertTrue("generate 应在列", "generate" in names)
        assertTrue("job_status 应在列", "job_status" in names)
        assertTrue(names.all { McpProtocol.isToolNameValid(McpProtocol.SERVER_NAME, it) })
    }

    @Test
    fun sessionIdIsEchoedWhenProvided() = withServer { port, _ ->
        val payload = """{"jsonrpc":"2.0","id":1,"method":"ping"}""".toByteArray()
        val connection = (URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.setFixedLengthStreamingMode(payload.size)
        connection.setRequestProperty("Authorization", "Bearer $TOKEN")
        connection.setRequestProperty("Mcp-Session-Id", "sess-42")
        connection.outputStream.use { it.write(payload) }
        assertEquals(200, connection.responseCode)
        assertEquals("sess-42", connection.getHeaderField("Mcp-Session-Id"))
        connection.disconnect()
    }

    @Test
    fun rejectsMissingOrWrongToken() = withServer { port, _ ->
        val (noToken, _) = postMcp(port, """{"jsonrpc":"2.0","id":1,"method":"initialize"}""", token = "")
        assertEquals(401, noToken)
        val (badToken, _) = postMcp(port, """{"jsonrpc":"2.0","id":1,"method":"initialize"}""", token = "wrong")
        assertEquals(401, badToken)
    }

    @Test
    fun unknownMethodReturnsJsonRpcError() = withServer { port, _ ->
        val (code, body) = postMcp(port, """{"jsonrpc":"2.0","id":5,"method":"does/not/exist"}""")
        assertEquals(200, code)
        assertEquals(-32601, JSONObject(body).getJSONObject("error").getInt("code"))
    }

    @Test
    fun malformedJsonReturnsParseError() = withServer { port, _ ->
        val (code, body) = postMcp(port, "{not json")
        assertEquals(200, code)
        assertEquals(-32700, JSONObject(body).getJSONObject("error").getInt("code"))
    }

    // ===== 工具调用 =====

    @Test
    fun callsListModelsThroughRealHttp() = withServer { port, _ ->
        val (code, body) = postMcp(
            port,
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_models","arguments":{"type":"lora"}}}""",
        )
        assertEquals(200, code)
        val text = JSONObject(body).getJSONObject("result")
            .getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(text.contains("a.safetensors"))
    }

    @Test
    fun generateReturnsDownloadableUrlAndFileIsServable() = withServer { port, fixture ->
        val (code, body) = postMcp(
            port,
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"generate","arguments":{"prompt":"a cat"}}}""",
        )
        assertEquals(200, code)
        val text = JSONObject(body).getJSONObject("result")
            .getJSONArray("content").getJSONObject(0).getString("text")
        val payload = JSONObject(text)
        assertEquals("done", payload.getString("status"))

        // 关键链路：响应里给出的 URL 必须真的能取到同样的字节。
        val url = payload.getJSONArray("images").getJSONObject(0).getString("url")
        val path = url.substringAfter("http://127.0.0.1:$port")
        val (fileCode, bytes, contentType) = get(port, path)
        assertEquals(200, fileCode)
        assertEquals("image/png", contentType)
        assertEquals(listOf<Byte>(1, 2, 3, 4), bytes.toList())
    }

    @Test
    fun toolsListIncludesNewTools() = withServer { port, _ ->
        val (_, body) = postMcp(port, """{"jsonrpc":"2.0","id":11,"method":"tools/list"}""")
        val tools = JSONObject(body).getJSONObject("result").getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertTrue("应含 describe_workflow", "describe_workflow" in names)
        assertTrue("应含 cancel_jobs", "cancel_jobs" in names)
        assertTrue("工具名仍需满足 64 字符上限", names.all { it.length <= McpProtocol.MAX_TOOL_NAME_LENGTH })
    }

    @Test
    fun describeWorkflowIsCallable() = withServer { port, _ ->
        val (code, body) = postMcp(
            port,
            """{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"describe_workflow","arguments":{}}}""",
        )
        assertEquals(200, code)
        val text = JSONObject(body).getJSONObject("result")
            .getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(text.contains("steps"))
    }

    @Test
    fun cancelJobsIsCallable() = withServer { port, _ ->
        val (code, body) = postMcp(
            port,
            """{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"cancel_jobs","arguments":{}}}""",
        )
        assertEquals(200, code)
        val text = JSONObject(body).getJSONObject("result")
            .getJSONArray("content").getJSONObject(0).getString("text")
        // 默认范围：只动本 App 提交的，并告知保留了多少非本 App 的。
        assertTrue("应说明只取消本 App 提交的", text.contains("本 App"))
        assertTrue("应提示保留了多少非本 App 的", text.contains("保留未动"))
    }

    @Test
    fun cancelJobsWithAllClearsEverything() = withServer { port, _ ->
        val (_, body) = postMcp(
            port,
            """{"jsonrpc":"2.0","id":14,"method":"tools/call","params":{"name":"cancel_jobs","arguments":{"all":true}}}""",
        )
        val text = JSONObject(body).getJSONObject("result")
            .getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue("all=true 时不应再说保留", !text.contains("保留未动"))
    }

    @Test
    fun cancelSingleJobReportsItsId() = withServer { port, _ ->
        val (_, body) = postMcp(
            port,
            """{"jsonrpc":"2.0","id":15,"method":"tools/call","params":{"name":"cancel_jobs","arguments":{"job_id":"abc123"}}}""",
        )
        val text = JSONObject(body).getJSONObject("result")
            .getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(text.contains("abc123"))
    }

    @Test
    fun generatePassesParamsThrough() = runBlocking {
        val host = FakeHost()
        val registry = McpToolRegistry(
            host,
            McpFileStore(cacheDir = java.io.File(System.getProperty("java.io.tmpdir"), "mcp-params-${System.nanoTime()}")),
        ) { "http://x/files/" }
        registry.dispatch(
            "tools/call",
            JSONObject(
                """{"name":"generate","arguments":{"prompt":"p","params":{"3::steps":"30","3::cfg":7.5}}}""",
            ),
            "1",
        )
        assertEquals("30", host.lastRequest?.params?.get("3::steps"))
        assertEquals("7.5", host.lastRequest?.params?.get("3::cfg"))
    }

    @Test
    fun jobStatusAcceptsMultipleIds() = withServer { port, _ ->
        // 提交多个任务后逐个查要 8 轮往返——一次传数组。
        val (code, body) = postMcp(
            port,
            """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"job_status","arguments":{"job_ids":["aaa","bbbq","cccr"]}}}""",
        )
        assertEquals(200, code)
        val text = JSONObject(body).getJSONObject("result")
            .getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue("应逐行给出每个 id 的状态", text.contains("aaa") && text.contains("bbbq") && text.contains("cccr"))
        assertTrue("排队中必须单独标出来", text.contains("queued"))
        assertTrue(text.contains("running"))
    }

    @Test
    fun jobStatusWithoutIdReportsError() = withServer { port, _ ->
        // 缺参数要回错误（模型据此补参数），不是静默返回空结果。
        val (_, body) = postMcp(
            port,
            """{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"job_status","arguments":{}}}""",
        )
        assertTrue(JSONObject(body).getJSONObject("result").getBoolean("isError"))
    }

    @Test
    fun generateTimeoutReturnsRunningWithJobId() = withServer { port, _ ->
        val (_, body) = postMcp(
            port,
            """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"generate","arguments":{"prompt":"slow"}}}""",
        )
        val text = JSONObject(body).getJSONObject("result")
            .getJSONArray("content").getJSONObject(0).getString("text")
        val payload = JSONObject(text)
        assertEquals("running", payload.getString("status"))
        assertEquals("job-run", payload.getString("job_id"))
    }

    @Test
    fun toolFailureIsReportedAsNormalResultNotRpcError() = withServer { port, _ ->
        // 工具执行失败按 MCP 约定回 isError=true 的**正常结果**，让模型能看到原因并调整，
        // 而不是把整轮 JSON-RPC 弄成错误。
        val (code, body) = postMcp(
            port,
            """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"generate","arguments":{"prompt":"fail"}}}""",
        )
        assertEquals(200, code)
        val result = JSONObject(body).getJSONObject("result")
        assertTrue(result.getBoolean("isError"))
    }

    @Test
    fun unknownToolIsReportedAsError() = withServer { port, _ ->
        val (_, body) = postMcp(
            port,
            """{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"nope","arguments":{}}}""",
        )
        assertTrue(JSONObject(body).getJSONObject("result").getBoolean("isError"))
    }

    // ===== 文件端点 =====

    @Test
    fun unknownFileIdReturns404() = withServer { port, _ ->
        val (code, _, _) = get(port, "/files/does-not-exist.png")
        assertEquals(404, code)
    }

    @Test
    fun healthEndpointResponds() = withServer { port, _ ->
        val (code, bytes, _) = get(port, "/health")
        assertEquals(200, code)
        assertTrue(String(bytes).contains("true"))
    }

    @Test
    fun unknownPathReturns404() = withServer { port, _ ->
        val (code, _, _) = get(port, "/nope")
        assertEquals(404, code)
    }

    @Test
    fun stoppedServerReleasesPort() = withServer { port, fixture ->
        assertEquals(200, get(port, "/health").first)
        fixture.server.stop()
        val failed = runCatching { get(port, "/health") }.isFailure
        assertTrue("停止后不应还能连上", failed)
    }

    @Test
    fun generateRequestParsesAllArguments() = runBlocking {
        val host = FakeHost()
        val registry = McpToolRegistry(
            host,
            McpFileStore(cacheDir = java.io.File(System.getProperty("java.io.tmpdir"), "mcp-arg-test-${System.nanoTime()}")),
        ) { "http://x/files/" }
        registry.dispatch(
            "tools/call",
            JSONObject(
                """{"name":"generate","arguments":{"prompt":"p","negative":"n","count":99}}""",
            ),
            "1",
        )
        val request = host.lastRequest
        assertNotNull(request)
        assertEquals("p", request!!.prompt)
        assertEquals("n", request.negative)
        assertEquals("count 必须夹到 1..8", 8, request.count)
    }

    private companion object {
        const val TOKEN = "test-token-123"

        /** 单次连接/读取上限。默认是无限——一旦服务端不回，测试会挂到 CI 超时。 */
        const val TIMEOUT_MILLIS = 5_000
    }
}
