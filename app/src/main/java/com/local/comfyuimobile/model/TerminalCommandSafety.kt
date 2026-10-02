package com.local.comfyuimobile.model

/**
 * AI 终端助手（v0.2.54）：把大模型给出的终端操作切成一条条可审阅的命令。
 *
 * 安全模型（这是本功能的核心约束，不是可选装饰）：
 *  1. **AI 只提议，不执行**。模型输出被解析成命令列表交给界面展示，用户逐条确认才发送。
 *  2. **危险命令默认拒绝**（[isDangerous]）。删除、覆盖、卸载、权限变更这类不可逆
 *     操作一律需要用户显式勾选"我确认"才会放行，避免模型一次误判毁掉用户的模型仓库。
 *  3. **只读命令可批量放行**（[isReadOnly]）。`ls`/`du`/`find` 之类探查命令没有副作用，
 *     每次都要求确认只会让用户点到手酸、最后无脑全点确认——那样安全边界反而失效。
 *
 * 纯 Kotlin：判定规则与解析都可脱离 Android SDK 单测。
 */
object TerminalCommandSafety {

    /**
     * 危险命令前缀（命中即需二次确认）。
     *
     * 刻意用"前缀/包含"而不是精确匹配：`rm -rf`、`/bin/rm`、`sudo rm` 都要能命中。
     */
    private val DANGEROUS_TOKENS = listOf(
        "rm", "rmdir", "dd", "mkfs", "shred", "truncate",
        "mv", "cp -f", "chmod", "chown", "chgrp",
        "apt", "apk", "yum", "dnf", "pacman",
        "pip uninstall", "conda remove", "npm uninstall",
        "git reset", "git clean", "git checkout", "git push",
        "systemctl", "service", "kill", "pkill", "reboot", "shutdown",
        ">", ">>", "tee", "sed -i", "find -delete", "find -exec",
    )

    /** 纯只读命令：无副作用，可以不用逐条确认。 */
    private val READ_ONLY_PREFIXES = listOf(
        "ls", "ll", "pwd", "cat", "head", "tail", "wc", "file",
        "du", "df", "stat", "which", "type", "echo", "date", "whoami",
        "find", "grep", "rg", "tree", "nvidia-smi", "free", "uptime",
        "top -b", "ps", "env", "printenv", "uname", "id", "hostname",
        "python --version", "python3 --version", "pip list", "pip show",
        "conda list", "conda info", "node --version", "npm list",
    )

    /**
     * 命令是否危险（需要用户显式确认）。
     *
     * 判定顺序很重要：先判只读。`find ~/models -name "*.safetensors"` 含 `find`，
     * 而 `find -delete` 是危险写操作——两者不能一起被前缀规则误伤到同一侧。
     */
    fun isDangerous(command: String): Boolean {
        val normalized = normalize(command)
        if (normalized.isBlank()) return false
        if (isReadOnly(normalized)) return false
        return DANGEROUS_TOKENS.any { token -> containsToken(normalized, token) }
    }

    /**
     * 是否属于"看了不改"的探查命令。
     *
     * 注意把 `find -delete` / `find -exec` 排除掉——它们同样以 `find` 开头。
     */
    fun isReadOnly(command: String): Boolean {
        val normalized = normalize(command)
        if (normalized.isBlank()) return false
        if (normalized.contains("-delete") || normalized.contains("-exec")) return false
        // 管道里若接了写命令（如 `cat x | tee y`），整体不再算只读。
        if (normalized.contains("|")) {
            val parts = normalized.split("|").map { it.trim() }
            if (parts.drop(1).any { part -> isDangerous(part) }) return false
        }
        return READ_ONLY_PREFIXES.any { prefix ->
            normalized == prefix || normalized.startsWith("$prefix ")
        }
    }

    /** 命令涉及的包管理器安装动作——这类操作（下载插件）是用户主要诉求，单独标出来。 */
    fun isInstall(command: String): Boolean {
        val normalized = normalize(command)
        return listOf(
            "pip install", "pip3 install", "pip uninstall",
            "conda install", "conda remove",
            "npm install", "npm i ", "apk add", "apt install", "apt-get install",
            "git clone", "wget", "curl -o", "curl -L -o", "unzip", "tar -x",
        ).any { normalized.startsWith(it) || normalized.contains(" $it") }
    }

    /**
     * 给命令加执行边界标记，便于从终端输出里切出"这一条命令的输出"。
     *
     * 终端输出是一条连续的流，若不加标记，模型无法判断命令跑完没有、输出到哪为止——
     * 上一轮命令的尾巴会被当成本轮结果，后续判断全错。用带随机 token 的 echo 包起来，
     * 再把退出码打出来，模型就能准确知道：结束标记之间的就是本次输出，`exit=N` 是结果。
     */
    fun wrap(command: String, token: String): String =
        "echo __AI_${token}_BEGIN__ && { $command ; } ; echo __AI_${token}_END__ rc=\$?"

    fun beginMarker(token: String): String = "__AI_${token}_BEGIN__"
    fun endMarker(token: String): String = "__AI_${token}_END__"

    /** 从输出行里解析退出码；不是结束标记行则返回 null。 */
    fun parseExitCode(line: String, token: String): Int? {
        val marker = endMarker(token)
        val index = line.indexOf(marker)
        if (index < 0) return null
        val rc = line.substring(index + marker.length).substringAfter("rc=", "").trim()
        return rc.toIntOrNull()
    }

    /**
     * 从终端缓冲里切出某条命令的输出（v0.2.61）。
     *
     * 为什么不能记"发送前的行数"当起点：终端缓冲只保留最近 TERMINAL_MAX_LINES 行，
     * 命令执行期间随时可能裁剪，绝对下标会整体偏移——切出的窗口可能漏掉结束标记，
     * 于是瞬间完成的命令也要白等满超时。token 标记是这条命令独有的，用它在**当前**
     * 缓冲里现找，天然免疫裁剪。
     *
     * 起点取 BEGIN 标记的**最后一次**出现：回显那行（整条 wrap 命令原样）与 echo 打印
     * 的标记行都含它，后者才是真正的输出起点。找不到标记（回显还没到）时返回 null，
     * 由调用方决定要不要等下一轮。
     */
    fun sliceOutput(lines: List<String>, token: String): CommandOutputWindow? {
        val beginIndex = lines.indexOfLast { it.contains(beginMarker(token)) }
        if (beginIndex < 0) return null
        val fresh = lines.drop(beginIndex + 1)
        val endIndex = fresh.indexOfFirst { parseExitCode(it, token) != null }
        if (endIndex < 0) return null
        val exitCode = parseExitCode(fresh[endIndex], token) ?: return null
        val body = fresh.take(endIndex)
            .filterNot { it.contains(beginMarker(token)) || it.contains(endMarker(token)) }
            .joinToString("\n")
        return CommandOutputWindow(output = body, exitCode = exitCode)
    }

    /**
     * 从模型回复里抽出命令行（形如 ```sh 代码块 或 `$ xxx` 行）。
     *
     * 只认明确的代码块/提示符号，不猜普通句子——把说明文字当命令执行是事故来源。
     */
    fun parseCommands(reply: String): List<String> {
        val commands = mutableListOf<String>()
        // fenced code block（```sh / ```bash / ```shell / ```）
        val fence = Regex("```(?:sh|bash|shell|zsh|console)?\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        fence.findAll(reply).forEach { match ->
            match.groupValues[1].lineSequence().forEach { line -> addCommandLine(commands, line) }
        }
        if (commands.isNotEmpty()) return commands.take(MAX_COMMANDS)
        // 退路：`$ cmd` / `> cmd` 形式的行
        reply.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("$ ") || trimmed.startsWith("> ")) {
                addCommandLine(commands, trimmed.drop(2))
            }
        }
        return commands.take(MAX_COMMANDS)
    }

    private fun addCommandLine(target: MutableList<String>, raw: String) {
        val line = raw.trim().removePrefix("$").trim().removePrefix("#").trim()
        if (line.isBlank()) return
        // 注释行、纯说明文字不当命令（要求至少有一个空格或看起来像可执行名）。
        if (line.startsWith("//")) return
        if (!line.first().isLetter() && !line.startsWith("./") && !line.startsWith("/")) return
        if (line in target) return
        target += line
    }

    /** 单次最多执行多少条命令：防模型一口气吐二十条把用户点爆。 */
    const val MAX_COMMANDS = 10

    /** 权限等级：1=每条都问 / 2=仅危险命令问 / 3=不问（v0.2.59）。 */
    const val LEVEL_ASK_ALL = 1
    const val LEVEL_ASK_DANGEROUS = 2
    const val LEVEL_ASK_NOTHING = 3

    /** 权限等级的展示文案（设置界面与说明共用一份，避免两处各写一句）。 */
    fun levelLabel(level: Int): String = when (level.coerceIn(1, 3)) {
        LEVEL_ASK_ALL -> "每条都问"
        LEVEL_ASK_DANGEROUS -> "危险才问"
        else -> "不问"
    }

    fun levelDescription(level: Int): String = when (level.coerceIn(1, 3)) {
        LEVEL_ASK_ALL -> "每条命令都要你点「执行」"
        LEVEL_ASK_DANGEROUS -> "只有危险命令（删除/覆盖/卸载）需要确认"
        else -> "AI 直接执行，不再询问（命令仍会记在对话里）"
    }

    /**
     * 在当前权限等级下，这条命令是否需要用户先确认才能执行（v0.2.59）。
     *
     * 1 级：全部需要；2 级：仅危险命令需要；3 级：都不需要。
     * 纯函数，可单测——权限判定是安全边界，不该散在 UI 里。
     */
    fun requiresConfirmation(command: String, level: Int): Boolean = when (level.coerceIn(1, 3)) {
        LEVEL_ASK_ALL -> true
        LEVEL_ASK_DANGEROUS -> isDangerous(command)
        else -> false
    }

    private fun normalize(command: String): String = command.trim().lowercase()

    /** 词边界匹配，避免 "rm" 命中 "format" 之类的子串。 */
    private fun containsToken(text: String, token: String): Boolean {
        if (token.contains(' ')) return text.contains(token)
        if (token == ">" || token == ">>") return text.contains(token)
        var index = text.indexOf(token)
        while (index >= 0) {
            val beforeOk = index == 0 || !text[index - 1].isLetterOrDigit()
            val after = index + token.length
            val afterOk = after >= text.length || !text[after].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            index = text.indexOf(token, index + 1)
        }
        return false
    }
}
