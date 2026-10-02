package com.local.comfyuimobile.model

/**
 * AI 回复的轻量排版解析（v0.2.63）。
 *
 * 为什么需要它：模型按守则要求把命令放进 ```sh 代码块、用 `**加粗**` 强调重点，
 * 但界面以前把整段文字直接原样显示——用户看到的是裸露的围栏和星号
 * （截图里的 `**只读**` 与单独一行的 ```sh），既难看又难读。
 *
 * 刻意只做**两件事**：切出代码块、去掉强调标记。不做完整 Markdown 渲染：
 *  - 终端助手要显示的是命令与短结论，不是文档；引入完整解析器只会带来
 *    「模型没闭合的星号把整段吃掉」这类新问题；
 *  - 纯 Kotlin、无依赖，可单测。
 */
object AssistantMarkup {

    /** 一段内容：要么是代码块（等宽显示），要么是普通文字。 */
    data class Block(
        val text: String,
        val isCode: Boolean,
        /** 代码块的语言标注（如 `sh`），普通文字为 null。仅用于显示提示。 */
        val language: String? = null,
    )

    /**
     * 把整段回复切成若干块。
     *
     * 围栏语法与 [TerminalCommandSafety.parseCommands] 保持一致（```lang … ```），
     * 但这里的目的是**显示**，所以未闭合的围栏也照常切出来（模型偶尔会漏写收尾围栏，
     * 那种情况把内容显示成代码块，总好过整段丢掉或混在正文里）。
     */
    fun parse(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        var index = 0
        while (index <= text.length) {
            val fence = text.indexOf(FENCE, index)
            if (fence < 0) {
                appendPlain(blocks, text.substring(index))
                break
            }
            appendPlain(blocks, text.substring(index, fence))
            // 围栏后紧跟语言标注，到行尾为止
            val afterFence = fence + FENCE.length
            val lineEnd = text.indexOf('\n', afterFence)
            if (lineEnd < 0) {
                // 只有围栏没有内容：当作普通文字，别凭空造一个空代码块
                appendPlain(blocks, text.substring(fence))
                break
            }
            val language = text.substring(afterFence, lineEnd).trim().ifBlank { null }
            val close = text.indexOf(FENCE, lineEnd + 1)
            if (close < 0) {
                blocks += Block(text.substring(lineEnd + 1).trimEnd(), isCode = true, language = language)
                break
            }
            blocks += Block(text.substring(lineEnd + 1, close).trimEnd(), isCode = true, language = language)
            index = close + FENCE.length
        }
        return blocks.filterNot { it.text.isBlank() && !it.isCode }
    }

    /**
     * 正文里的强调标记去掉，只留文字。
     *
     * 只处理成对的 `**`：单个星号留着——它在命令与通配符里是有意义的
     * （`rm -rf *`、`*.safetensors`），误删会让人误读命令。
     */
    fun plain(text: String): String {
        var result = text
        while (true) {
            val open = result.indexOf("**")
            if (open < 0) break
            val close = result.indexOf("**", open + 2)
            if (close < 0) break // 落单的星号：原样保留，别把后半段吃掉
            result = result.substring(0, open) + result.substring(open + 2, close) + result.substring(close + 2)
        }
        return result.replace("`", "")
    }

    private fun appendPlain(target: MutableList<Block>, raw: String) {
        val cleaned = plain(raw).trim('\n', ' ', '\t')
        if (cleaned.isNotBlank()) target += Block(cleaned, isCode = false)
    }

    private const val FENCE = "```"
}
