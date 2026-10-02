package com.local.comfyuimobile.model

/**
 * 终端转义序列的剥离（v0.2.66）。
 *
 * 为什么需要它：智能助手把命令输出直接贴进对话时，带的是**原始 PTY 字节流**——
 * `ls` 这类命令会输出颜色（`ESC[0m`、`ESC[01;36m` 一类的 SGR 序列）。
 * 控制台那边有一整套解析渲染，但智能助手这条路径没有，于是真机上直接显示成
 * `[0m[01;36mComfyUI[0m` 这样的乱码，用户看不懂。
 *
 * 这里做成**剥离**（而不是着色渲染），因为：
 *  - 会话气泡里的命令输出是窄栏，着色的收益远小于乱码的代价；
 *  - 喂给大模型的也该是纯文本——颜色码只会平白多占 token，还可能干扰模型判断。
 * 控制台（ConsoleScreen）保持原有的着色渲染，不受影响。
 *
 * 纯 Kotlin，可单测。
 */
object AnsiText {

    /**
     * 匹配各类转义序列。
     *
     * 四种形态（按出现频率）：
     *  1. `ESC[…m` —— SGR（颜色/加粗），`ls`、`git`、彩色提示符都会用；
     *  2. `ESC[…X` —— 其它 CSI（清行 `ESC[K`、光标移动等）；
     *  3. `ESC]…BEL` / `ESC]…ESC\` —— OSC（终端标题、超链接）；
     *  4. `ESC X` —— 两字符序列。
     *
     * 与 ConsoleScreen 里的 `TERMINAL_ESCAPE` 保持同一套规则——两边不一致会导致
     * 同一个输出在控制台正常、在助手页乱码（这正是本次问题的成因）。
     */
    private val ESCAPE = Regex(
        "\u001B\\[([0-9;]*)m" +
            "|\u001B\\[[0-9;?]*[ -/]*[@-~]" +
            "|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)" +
            "|\u001B[@-Z\\\\-_]",
    )

    /**
     * 去掉所有终端转义序列与其余不可见控制字符。
     *
     * 保留 `\n` 与 `\t`——它们是有意义的排版信息。
     */
    fun strip(raw: String): String {
        val withoutEscapes = ESCAPE.replace(raw, "")
        return buildString(withoutEscapes.length) {
            for (ch in withoutEscapes) {
                when {
                    ch == '\n' || ch == '\t' -> append(ch)
                    // `\r` 单独出现是"回到行首重写"（进度条）——这里只当分隔处理，
                    // 保留会让同一行内容重复显示。
                    ch == '\r' -> append('\n')
                    // 可见字符保留；DEL(127) 虽 >=32 但同样是控制字符，要丢掉
                    ch.code in 32..126 -> append(ch)
                    ch.code >= 160 -> append(ch)
                    else -> Unit
                }
            }
        }
    }

    /**
     * 终端的 ANSI 转义里有一种"光标回退"写法会产生重复行（如 `\r` 重写进度条），
     * 这里顺手把连续重复的空行压成一个，让输出更紧凑。
     */
    fun tidy(text: String): String {
        val lines = strip(text).split('\n').map { it.trimEnd() }
        val result = mutableListOf<String>()
        var blankRun = 0
        for (line in lines) {
            if (line.isEmpty()) {
                blankRun++
                // 最多留一个空行：终端里为了刷屏常连打多个空行
                if (blankRun <= 1) result.add("")
            } else {
                blankRun = 0
                result.add(line)
            }
        }
        while (result.isNotEmpty() && result.last().isEmpty()) result.removeAt(result.lastIndex)
        return result.joinToString("\n")
    }
}
