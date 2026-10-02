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
 *  4. **灾难性命令有独立熔断**（[isCatastrophic]）。清空根目录/家目录、格式化、
 *     重启实例这类操作**不受权限等级影响**，永远需要人工确认——即使用户选了「不问」。
 *     这是防模型误判的最后一道闸（对齐 Claude Code 的 circuit breaker 设计）。
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
        ">", ">>", "tee", "sed -i",
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
        if (DANGEROUS_TOKENS.any { token -> containsToken(normalized, token) }) return true
        return hasDestructiveFindAction(normalized)
    }

    /**
     * `find ... -delete` / `find ... -exec rm` 是写操作，但危险词表按字面量匹配
     * 抓不到它们（`find -delete` 这种连写的 token 在真实命令里不存在——两者之间隔着
     * 路径与条件表达式）。
     *
     * 修前实测：`isDangerous("find ~/models -name \"*.tmp\" -delete")` 返回 **false**，
     * 而 `find` 又不在危险词表里——这条命令会被当成安全命令直接放行。
     * 判定改为按词元拆开：`find` + 独立出现 `-delete`/`-exec` 才算。
     */
    private fun hasDestructiveFindAction(normalized: String): Boolean {
        val tokens = normalized.split(' ', '\t')
        if (tokens.none { it == "find" }) return false
        return tokens.any { it == "-delete" || it == "-exec" || it == "-execdir" }
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
        // 重定向写文件不算只读（v0.2.62 修）：`echo x > /etc/hosts` 曾因前缀是 echo
        // 被当成只读整体放行——覆盖写文件反而完全不用确认。写到 /dev/null 一类的例外
        // 保留（`nvidia-smi 2>/dev/null` 太常见，且无害）。
        if (hasUnsafeRedirect(normalized)) return false
        // 管道里若接了写命令（如 `cat x | tee y`），整体不再算只读。
        if (normalized.contains("|")) {
            val parts = normalized.split("|").map { it.trim() }
            if (parts.drop(1).any { part -> isDangerous(part) }) return false
        }
        return READ_ONLY_PREFIXES.any { prefix ->
            normalized == prefix || normalized.startsWith("$prefix ")
        }
    }

    /**
     * 是否存在“写到真实文件”的重定向（`> f` / `>> f`）。
     *
     * 排除 `/dev/null` 这类空设备：`2>/dev/null` 是极常见的无害写法，
     * 若一并算成写操作，`nvidia-smi 2>/dev/null` 这种探查命令也会要求确认。
     */
    private fun hasUnsafeRedirect(normalized: String): Boolean =
        REDIRECT.findAll(normalized).any { match ->
            match.groupValues[1].trim('"', '\'') !in SAFE_DEV_NODES
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
    fun parseCommands(reply: String): List<String> = parseAllCommands(reply).take(MAX_COMMANDS)

    /**
     * 与 [parseCommands] 相同，但**不做** [MAX_COMMANDS] 截断（v0.2.62）。
     *
     * 调用方用它拿到全部命令数，好在超限时明确告诉用户"还有 N 条没列出"——
     * 以前的截断是静默的：模型给了 15 条也只显示 10 条，用户不知道丢了什么。
     */
    fun parseAllCommands(reply: String): List<String> {
        val commands = mutableListOf<String>()
        // fenced code block（```sh / ```bash / ```shell / ```）
        val fence = Regex("```(?:sh|bash|shell|zsh|console)?\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        fence.findAll(reply).forEach { match ->
            match.groupValues[1].lineSequence().forEach { line -> addCommandLine(commands, line) }
        }
        if (commands.isNotEmpty()) return commands
        // 退路：`$ cmd` / `> cmd` 形式的行
        reply.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("$ ") || trimmed.startsWith("> ")) {
                addCommandLine(commands, trimmed.drop(2))
            }
        }
        return commands
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
        else -> "AI 直接执行，不再询问；灾难性操作仍会要求确认（命令仍会记在对话里）"
    }

    /**
     * 在当前权限等级下，这条命令是否需要用户先确认才能执行（v0.2.59）。
     *
     * 1 级：全部需要；2 级：仅危险命令需要；3 级：都不需要。
     * **例外：灾难性命令任何等级都要确认**（v0.2.62 熔断器）。
     * 纯函数，可单测——权限判定是安全边界，不该散在 UI 里。
     */
    fun requiresConfirmation(command: String, level: Int): Boolean {
        if (isCatastrophic(command)) return true
        return when (level.coerceIn(1, 3)) {
            LEVEL_ASK_ALL -> true
            LEVEL_ASK_DANGEROUS -> isDangerous(command)
            else -> false
        }
    }

    /**
     * 灾难性命令：**不受权限等级影响，永远需要人工确认**（v0.2.62）。
     *
     * 为什么要独立于 [isDangerous]：危险命令在 3 级「不问」下是可自动执行的——那是用户
     * 明确授予的权限。但「清空整个实例」「格式化磁盘」「重启关机」这类操作一旦出错，
     * 后果不是"某次操作失败"，而是用户的整个云端环境没了。模型误判的概率再低，
     * 也不该用"用户自己选的"当理由放行。Claude Code 的官方文档把这条写得很死：
     * 即使开启跳过所有权限，关键路径的 rm 也永远不自动放行——这是熔断器，不是权限。
     */
    fun isCatastrophic(command: String): Boolean {
        val normalized = normalize(command)
        if (normalized.isBlank()) return false
        if (FORK_BOMB.containsMatchIn(normalized)) return true
        if (writesToSystemPath(normalized)) return true

        val tokens = normalized.split(' ', '\t').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val verb = commandVerb(tokens)
        if (verb in SHUTDOWN_TOKENS) return true
        if (verb == "mkfs" || verb?.startsWith("mkfs.") == true) return true
        if (tokens.any { it.startsWith("of=/dev/") && it !in SAFE_DEV_NODES }) return true

        val recursiveFlag = tokens.any { RECURSIVE_FLAG.matches(it) }
        val chmodLike = tokens.any { it == "chmod" || it == "chown" }
        if (chmodLike && recursiveFlag && tokens.any { isCriticalTarget(it) }) return true

        val deleting = tokens.any { it == "rm" || it == "rmdir" || it == "shred" } ||
            (tokens.any { it == "find" } && tokens.any { it == "-delete" || it == "-exec" })
        if (!deleting) return false
        // `rmdir`/`find -delete` 本身就是递归语义，不要求 -r 标记。
        val recursive = recursiveFlag ||
            tokens.any { it == "rmdir" || it == "-delete" || it == "-exec" }
        if (!recursive) return false
        if (tokens.any { it == "--no-preserve-root" }) return true
        return tokens.any { isCriticalTarget(it) }
    }

    /**
     * 判断命令行里的**命令动词**（跳过 `sudo`、环境变量赋值与选项）。
     *
     * 只认命令位是为了避免误报：`grep shutdown /var/log/x` 里的 `shutdown` 是参数，
     * 不是真去关机。误报会让用户对确认弹窗麻木，反而削弱安全边界。
     */
    private fun commandVerb(tokens: List<String>): String? = tokens.firstOrNull {
        !it.startsWith("-") && !it.contains('=') && it !in PREFIX_WORDS
    }

    /**
     * 命令是否会写到系统目录（`> /etc/x`、`>> /usr/x`、`| tee /boot/x`）。
     *
     * `/dev/null` 这类空设备是常见且无害的写法（`2>/dev/null`），必须排除——
     * 否则几乎每条带日志重定向的命令都会误报。
     */
    private fun writesToSystemPath(normalized: String): Boolean {
        val targets = buildList {
            REDIRECT.findAll(normalized).forEach { add(it.groupValues[1]) }
            TEE.findAll(normalized).forEach { add(it.groupValues[1]) }
        }
        return targets.any { raw ->
            val target = raw.trim('"', '\'')
            target !in SAFE_DEV_NODES &&
                SYSTEM_WRITE_DIRS.any { dir -> target == dir || target.startsWith("$dir/") }
        }
    }

    /**
     * 命令的目标是否落在"关键路径"上：整个系统或用户全部数据所在的位置。
     *
     * 判定刻意保守——只有**目标本身就是**这些位置、或落在**纯系统目录的任意层级**下
     * 才算（`/usr/bin` 是灾难，删掉系统就废了）；`rm -rf ~/models/loras` 这类有明确
     * 目标子目录的删除不算（仍按危险命令确认）。
     */
    private fun isCriticalTarget(raw: String): Boolean {
        val target = raw.trim('"', '\'', '\\')
        if (target in CRITICAL_EXACT) return true
        if (HOME_ROOT.matches(target) || HOME_ROOT_GLOB.matches(target)) return true
        return SYSTEM_DIRS.any { dir -> target.startsWith("$dir/") }
    }

    private val FORK_BOMB = Regex(""":\s*\(\s*\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:""")

    /** 直接重启/关机（命令位）。`systemctl reboot` 这类交由危险命令确认即可。 */
    private val SHUTDOWN_TOKENS = setOf("shutdown", "reboot", "poweroff", "halt")

    /** `sudo`、`nohup` 这类前缀词，判定命令动词时跳过。 */
    private val PREFIX_WORDS = setOf("sudo", "nohup", "time", "env", "command", "exec", "doas")

    /** 递归标记：`-r` / `-rf` / `-fr` / `-R`（已 lowercase）。 */
    private val RECURSIVE_FLAG = Regex("^-[a-z]*r[a-z]*$")

    /** 重定向目标，如 `> /etc/x` 里的 `/etc/x`。 */
    private val REDIRECT = Regex(">>?\\s*([^\\s|;&<>]+)")

    /** `tee` 的目标，如 `... | tee -a /boot/x`。 */
    private val TEE = Regex("\\btee\\s+(?:-a\\s+)?([^\\s|;&<>]+)")

    /** 空设备：写它们无害，必须排除以免 `2>/dev/null` 误报。 */
    private val SAFE_DEV_NODES = setOf(
        "/dev/null", "/dev/stdout", "/dev/stderr", "/dev/tty",
        "/dev/zero", "/dev/random", "/dev/urandom",
    )

    /**
     * 关键路径目标（整词比对）。
     *
     * 含通配形式（单独的 `*`，以及家目录下的通配）：`rm -rf *` 在项目根目录下
     * 等同清空用户数据。
     */
    private val CRITICAL_EXACT = setOf(
        "/", "/*", "*", "./*", "../*",
        "~", "~/", "~/*", "\$home", "\$home/", "\$home/*",
        "\${home}", "\${home}/", "\${home}/*",
        "/etc", "/usr", "/bin", "/sbin", "/lib", "/lib64", "/boot", "/dev", "/proc", "/sys",
        "/var", "/root", "/opt", "/home",
    )

    /** 纯系统目录：其**任意层级**的后代都不允许被递归删改。 */
    private val SYSTEM_DIRS = setOf(
        "/etc", "/usr", "/bin", "/sbin", "/lib", "/lib64", "/boot", "/dev", "/proc", "/sys",
    )

    /** 写操作（重定向/tee）不该落到的系统目录。 */
    private val SYSTEM_WRITE_DIRS = setOf(
        "/etc", "/usr", "/bin", "/sbin", "/lib", "/lib64", "/boot",
    )

    private val HOME_ROOT = Regex("^/home/[a-z0-9_.-]+/?$")
    private val HOME_ROOT_GLOB = Regex("^/home/[a-z0-9_.-]+/\\*$")

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
