package com.local.comfyuimobile.mcp

/**
 * 终端命令的「包裹—提取」逻辑（v0.2.97）。纯函数，可单测。
 *
 * 为什么要包裹：Jupyter 终端（terminado）**不告诉你命令什么时候跑完**——协议里
 * 没有结束标记，只回 `["stdout", 内容]`。所以把命令改成
 *
 * ```
 * ls ~/ComfyUI/models; echo "__CM_a3f2__:$?"
 * ```
 *
 * 读到 `__CM_a3f2__:0` 就知道跑完了，顺带拿到 exit code（AI 才知道成功还是失败）。
 *
 * **最容易被忽略的一点**：PTY 会把「你敲进去的那行」原样回显，所以缓冲区里会出现
 * 两次标记——第一次是回显（`echo "__CM_a3f2__:$?"`，后面跟的是字面 `$?`），
 * 第二次才是真正的结果（后面跟数字）。取「第一次出现」会拿到空输出，
 * 所以这里只把**后面跟着数字**的那种当结果，并且要取最后一个。
 */
internal object TerminalShell {

    /** 单条命令的默认等待上限（秒）。交互式命令（如裸 `python`）会超时，必须能返回。 */
    const val DEFAULT_TIMEOUT_SECONDS = 30

    /** 返回给模型的输出上限（字符）。`find /` 这类能刷几千行，必须限长。 */
    const val MAX_OUTPUT_CHARS = 16_000

    /** 缺省显示的尾部行数（截断时保尾部，因为结论通常在最后）。 */
    private const val TAIL_LINES_ON_TRUNCATE = 60

    /** 标记前缀。带上调用方给的随机 token，避免和命令自身的输出撞车。 */
    fun marker(token: String): String = "__CM_${token}__"

    /**
     * 把命令包成「跑完打标记」的形式。`$?` 在双引号里会被 shell 展开成退出码。
     *
     * **后台命令（`&` 结尾）必须用换行而不是分号**（v0.2.99 修 P0）：
     * `cmd &; echo ...` 是 **bash 语法错误**（`&` 本身就是命令分隔符，后面不能再跟 `;`），
     * 整行都不会执行——于是标记永不出现，只能干等到超时，而用户看到的是
     * "命令没结束"，完全看不出是语法错误。已用 bash 实测：该写法 exit=2、stdout 为空。
     * `cmd &` + 换行 是合法分隔（实测 exit=0）。
     *
     * 这条对主链是阻断性的：**启动 ComfyUI 必须后台跑**（前台跑永不退出），
     * 而 `bash start.sh &` / `nohup bash _st.sh > log 2>&1 &` 是最经典的写法。
     */
    fun wrap(command: String, token: String): String {
        val cmd = command.trimEnd()
        val separator = if (cmd.endsWith("&")) "\n" else "; "
        return "$cmd$separator" + "echo \"${marker(token)}:\$?\""
    }

    /** 该命令是不是后台运行（以 `&` 结尾）。 */
    fun isBackground(command: String): Boolean = command.trimEnd().endsWith("&")

    /**
     * 提取结果。
     *
     * @param buffer 该终端到目前为止累积的输出。
     * @param token 本次调用用的随机 token（与 [wrap] 同一个）。
     * @param maxChars 输出上限。
     */
    fun extract(buffer: String, token: String, maxChars: Int = MAX_OUTPUT_CHARS): Result {
        val mark = marker(token)
        val normalized = buffer.replace("\r\n", "\n").replace('\r', '\n')
        // 只看「标记后面跟着 :数字」的，那才是真正的结果行；回显行后面是字面 "$?"。
        val resultRegex = Regex(Regex.escape(mark) + """:(\d+)""")
        val matches = resultRegex.findAll(normalized).toList()
        if (matches.isEmpty()) {
            // 还没跑完（或纯回显）。把回显那行也去掉，免得模型以为输出就是那条命令。
            return Result(text = trimOutput(stripEcho(normalized, mark), maxChars), exitCode = null, finished = false)
        }
        val last = matches.last()
        val body = normalized.substring(0, last.range.first)
        return Result(
            text = trimOutput(stripEcho(body, mark), maxChars),
            exitCode = last.groupValues[1].toIntOrNull(),
            finished = true,
        )
    }

    /**
     * 去掉命令回显那一行。
     *
     * 回显行里含标记（因为命令里有 `echo "__CM_x__:$?"`）。找到**不带数字**的那次
     * 出现，连同它所在行的换行一起去掉；找不到（比如终端没开回显）就原样返回。
     */
    private fun stripEcho(text: String, mark: String): String {
        val idx = text.indexOf(mark)
        if (idx < 0) return text
        val after = idx + mark.length
        val isResult = after + 1 < text.length && text[after] == ':' && text[after + 1].isDigit()
        // 只遇到结果行：本就没回显，保留全部。
        if (isResult) return text
        // 命中回显行：它结束在下一个换行，之后的正文才是命令输出。
        val lineEnd = text.indexOf('\n', idx)
        return if (lineEnd < 0) "" else text.substring(lineEnd + 1)
    }

    /** 去掉首尾空行，超长时保尾部并显式说明截断。 */
    private fun trimOutput(text: String, maxChars: Int): String {
        val trimmed = text.trim('\n')
        if (trimmed.length <= maxChars) return trimmed
        val lines = trimmed.lines()
        val tail = lines.takeLast(TAIL_LINES_ON_TRUNCATE).joinToString("\n")
        // 保尾部但也要限长：尾部本身可能就超（一行几千字符）。
        val capped = if (tail.length <= maxChars) tail else tail.takeLast(maxChars)
        return "…（输出过长已截断，以下为末尾部分）\n" + capped
    }

    /**
     * @param text 给模型看的输出（已去回显、可能已截断）
     * @param exitCode 退出码；null 表示标记还没出现（命令未结束）
     * @param finished 标记是否出现。false 时调用方要告诉模型「命令可能还在跑 /
     *   可能是交互式的」，不能让它以为这是完整输出。
     */
    data class Result(val text: String, val exitCode: Int?, val finished: Boolean)
}
