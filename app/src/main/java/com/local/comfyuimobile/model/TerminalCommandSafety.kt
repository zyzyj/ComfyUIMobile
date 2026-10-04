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
        // v0.2.74（安全修复）：**整条命令的每一段都必须只读**。
        //
        // 以前只检查了管道 `|`，漏掉 `&&`、`||`、`;`、`$(`、反引号——于是
        // `ls && rm -rf ~/models` 因为以只读命令开头被判成"只读"，
        // 而 isDangerous 对只读直接短路，默认档位（2）下**自动执行、不弹确认**。
        // 这是真漏洞：档位 2 的界面承诺是"删除/覆盖等仍需确认"。
        //
        // 分段后要求：① 每段都只读（递归判定）② 第一段是只读前缀。
        // 后者防的是 `rm -rf x && ls`（以危险命令开头）被当成只读。
        val segments = splitBySeparators(normalized)
        if (segments.size > 1) {
            if (segments.any { segment -> !isReadOnly(segment) }) return false
        }
        val head = segments.firstOrNull()?.trim().orEmpty()
        if (head.isBlank()) return false
        return READ_ONLY_PREFIXES.any { prefix ->
            head == prefix || head.startsWith("$prefix ")
        }
    }

    /**
     * 按 shell 的分隔符把命令切成若干段（v0.2.74）。
     *
     * 覆盖：`&&`、`||`、`;`、`|`、`$(`、反引号。都是"这条命令里还会跑别的命令"的信号——
     * 只要其中任意一段不是只读，整条就不能算只读。
     *
     * 刻意**不**处理转义与引号（如 `echo 'a;b'` 里的分号）：那需要真正的 shell 解析，
     * 而这里的方向必须是"宁可多判危险"——把字面量分号也当分隔符，最坏结果是
     * 一条安全的只读命令多要一次确认，而不是漏放一条危险命令。
     */
    private fun splitBySeparators(command: String): List<String> =
        command.split(*SHELL_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 命令分隔符（v0.2.74）。
     *
     * 双字符的 `&&` / `||` **必须排在**单字符 `&` / `|` 之前——Kotlin 的
     * `split(vararg delimiters)` 按数组顺序逐个拆，先拆长的才不会把 `&&` 碎成两个 `&`。
     *
     * 单字符 `&` 是"放到后台执行"：`ls & rm -rf x` 会让两件事都发生，必须分段。
     * （这条是补完 `&&`/`;`/`$(`/反引号之后做对抗性验证才发现的漏网。）
     *
     * v0.2.75 补 `<(` / `>(`（进程替换）：`ls <(rm -rf x)` 里的子命令同样会执行
     * （这条是外部复审指出的同类残留）。
     */
    private val SHELL_SEPARATORS = arrayOf("&&", "||", ";", "|", "&", "<(", ">(", "\$(", "`")

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
        // v0.2.75（安全修复）：与 isReadOnly / isCatastrophic 一样要**分段**。
        //
        // 以前只看开头（startsWith / contains），于是「以安装开头」后面接什么都放行：
        //   wget https://x/install.sh && sh install.sh     ← 下载脚本再执行（等于任意代码）
        //   git clone u && cd d && ./install.sh            ← 装插件的标准写法
        //   unzip p.zip && ./install
        //   pip install x && python -c "…rmtree…"
        // 而档位 2（默认）的放行条件里就有 isInstall → 这些全都被自动执行。
        //
        // 新规则：**首段命中安装/下载，且其余段必须全部只读**。
        // 为什么其余段要求只读而不是也允许安装：安装动作本身就免确认，
        // 若允许任意多段安装，`wget x && sh x` 这类"下载后执行"就又能绕过去
        // （`sh x` 不是安装也不是只读）。宁可多要一次确认。
        val segments = splitBySeparators(normalized)
        if (segments.isEmpty()) return false
        if (!matchesInstall(segments.first())) return false
        if (segments.size == 1) return true
        return segments.drop(1).all { segment -> isReadOnly(segment) }
    }

    /** 该段是否以安装/下载动作开头（沿用原有匹配规则）。 */
    private fun matchesInstall(segment: String): Boolean =
        INSTALL_PREFIXES.any { prefix -> segment.startsWith(prefix) || segment.contains(" $prefix") }

    /** 安装/下载类动作（与旧实现同一份清单）。 */
    private val INSTALL_PREFIXES = listOf(
        "pip install", "pip3 install", "pip uninstall",
        "conda install", "conda remove",
        "npm install", "npm i ", "apk add", "apt install", "apt-get install",
        "git clone", "wget", "curl -o", "curl -L -o", "unzip", "tar -x",
    )

    /**
     * 把命令整理成能安全嵌入 `{ … ; }` 包装的形态（v0.2.78）。
     *
     * 模型给的命令直接拼进包装会破坏包装本身，已实测两类：
     *  - 行尾 `# 注释`：`#` 后整段（含闭合 `}` 与结束标记）都被注释吃掉 →
     *    bash 报语法错误、BEGIN/END 一个都不输出 → 命令白等满 10 分钟超时。
     *  - 行尾悬空的 `;` / `&&` / `||` / `|` / `&`：拼成 `{ cmd; ; }` 同样语法错误，
     *    后果相同。
     *
     * 只清理**引号外**的内容（引号内 `#`、分号是命令的一部分，如 `echo "a # b"`）；
     * `#` 只在词首才算注释（`echo a#b` 里的 `#` 是字面量）。
     * 清理后若什么都不剩，返回 `:`（no-op）——至少能让这条命令正常"完成"。
     */
    fun sanitizeCommand(command: String): String {
        val trimmed = stripTrailingEmptyOperators(stripUnquotedComment(command))
        return trimmed.ifBlank { ":" }
    }

    /** 截断引号外的行尾注释；引号内的 `#` 与转义的 `\#` 保留。 */
    private fun stripUnquotedComment(command: String): String {
        var inSingle = false
        var inDouble = false
        var prev: Char? = null
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                inSingle -> if (c == '\'') inSingle = false
                inDouble -> when (c) {
                    '\\' -> i++ // 双引号内反斜杠转义
                    '"' -> inDouble = false
                }
                c == '\\' -> i++ // 引号外的反斜杠转义
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == '#' && (prev == null || prev.isWhitespace() || prev in "|&;()<>") ->
                    return command.substring(0, i)
            }
            prev = c
            i++
        }
        return command
    }

    /**
     * 剥离行尾悬空的命令分隔符（`;`、`&&`、`||`、`|`、`&`）。
     *
     * 它们本身不是完整命令，拼进 `{ cmd; ; }` 是语法错误（同注释一样会挂满超时）。
     * 剥离 `&` 会把"后台执行"改成前台——对 App 反而是对的：它靠结束标记判定完成，
     * 后台命令只会"假完成"（还报成功），本来就是守则禁止的写法。
     */
    private fun stripTrailingEmptyOperators(command: String): String {
        var result = command.trim()
        while (true) {
            val next = when {
                result.endsWith("&&") || result.endsWith("||") -> result.dropLast(2)
                result.endsWith(";") || result.endsWith("|") || result.endsWith("&") -> result.dropLast(1)
                else -> return result.trim()
            }
            result = next.trim()
            if (result.isEmpty()) return ""
        }
    }

    /**
     * 给命令加执行边界标记，便于从终端输出里切出"这一条命令的输出"。
     *
     * 终端输出是一条连续的流，若不加标记，模型无法判断命令跑完没有、输出到哪为止——
     * 上一轮命令的尾巴会被当成本轮结果，后续判断全错。用带随机 token 的 echo 包起来，
     * 再把退出码打出来，模型就能准确知道：结束标记之间的就是本次输出，`exit=N` 是结果。
     *
     * v0.2.78 两处加固（都是"生成物交给真实 shell 后语义变了"类问题的实测修复）：
     *  - 包装前先 [sanitizeCommand]，防行尾注释/悬空分隔符破坏包装；
     *  - 子 shell 里打开 pipefail：守则建议过的 `cmd | tail -N` 写法会让 `$?` 取
     *    最后一段（tail）的退出码——安装失败也报 rc=0。pipefail 让管道取首个非零值。
     *    141（SIGPIPE，下游提前关闭管道）归一化为 0：`cat big | head` 属正常用法。
     *    用完 restore，避免污染这个长期存活的终端会话。
     */
    fun wrap(command: String, token: String): String =
        "echo __AI_${token}_BEGIN__ && { set -o pipefail 2>/dev/null || true; " +
            "${sanitizeCommand(command)} ; rc=\$? ; if [ \$rc -eq 141 ]; then rc=0; fi; " +
            "set +o pipefail 2>/dev/null || true; } ; echo __AI_${token}_END__ rc=\$rc"

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
        val line = raw.trim().removePrefix("$").trim()
        if (line.isBlank()) return
        // v0.2.78：`#` 开头的行一律不当命令。
        //
        // 以前这里是 removePrefix("#")——想兼容"root 提示符"写法（`# ls`）。
        // 但 AI 回复的代码块里 `#` 开头的行绝大多数是**注释**，而中文的
        // isLetter() 为 true、英文注释的首词也像程序名，注释就这样被当成命令
        // 提取出来（实测 `# 先看显存` + `nvidia-smi` → 提取出 ['先看显存', 'nvidia-smi']）：
        // 档位 2 让用户点一条中文句子；档位 3 真发出去报 command not found；
        // 还挤占 MAX_COMMANDS 名额。提示符场景让用户手删那个 `#` 即可。
        if (line.startsWith("#")) return
        // 非 ASCII 的行不是命令（中文说明文字的兜底——真实命令与路径都是 ASCII）。
        if (line.any { it.code > 127 }) return
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
        // v0.2.64：原名「危险才问」与行为不符（那时每条都还要手点一下）。
        // 现在档位真的会放行命令，名字必须说清放行的是什么。
        LEVEL_ASK_DANGEROUS -> "只读与安装"
        else -> "不问"
    }

    fun levelDescription(level: Int): String = when (level.coerceIn(1, 3)) {
        LEVEL_ASK_ALL -> "每条命令都要你点「执行」"
        LEVEL_ASK_DANGEROUS -> "只读、安装、下载类命令自动执行；删除/覆盖等仍需确认"
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
     * 在当前权限等级下，这条命令是否可以**自动执行**（不必用户点「执行」）。
     *
     * 为什么要有它（v0.2.64 修）：以前档位只决定"要不要再点一次确认"，**所有命令**
     * 都还得用户先点一下「执行」——于是「危险才问」与「每条都问」在体感上没有区别，
     * 用户看到 `ls` 也要点，直接质疑"这不是危险才问吗"。档位名与行为对不上。
     *
     * 放行规则用的是**白名单**而不是"不在危险名单里就放行"：
     * `python -c "import shutil; shutil.rmtree('~/models')"` 这类命令既不在危险名单、
     * 也不是只读，黑名单思路会静默自动执行它。危险名单是启发式的，判错的方向必须偏保守——
     * 宁可多问一次，不可漏放一次。
     *
     * 各档位：
     *  - 1 每条都问：一律不自动执行
     *  - 2 只读与安装：只读命令、安装/下载类自动执行（这正是用户的主要诉求：
     *    看状态、装插件）；其它（含无法分类的）仍然要确认
     *  - 3 不问：除灾难性命令外都自动执行
     */
    fun autoRunnable(command: String, level: Int): Boolean {
        if (isCatastrophic(command)) return false
        return when (level.coerceIn(1, 3)) {
            LEVEL_ASK_ALL -> false
            LEVEL_ASK_DANGEROUS ->
                (isReadOnly(command) || isInstall(command)) && !isDangerous(command)
            else -> true
        }
    }

    /**
     * 一次命令执行完之后，连续失败计数应该怎么变（v0.2.71）。
     *
     * 三种结果要分开对待，尤其**超时不是失败**：
     *  - 超时（拿不到退出码）：命令可能只是慢——装依赖、下模型动辄十几分钟，
     *    而等待上限是 10 分钟。以前一律记成失败，连续 3 条慢命令就触发
     *    「已暂停自动执行」的熔断提示，属于误报。计数**不变**。
     *  - 成功（退出码 0）：清零。
     *  - 失败（拿到非零退出码）：+1。
     *
     * 抽成纯函数是为了把"超时不算失败"这条判断锁住——它靠真机反馈才发现，
     * 写在这里比散在 ViewModel 的分支里好测。
     */
    fun nextFailureCount(current: Int, exitCode: Int?): Int = when {
        exitCode == null -> current
        exitCode == 0 -> 0
        else -> current + 1
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

        // v0.2.74（安全修复）：复合命令要**逐段**判定，不能只看第一段的动词。
        //
        // 以前 commandVerb 只在整条命令里取首个非选项 token，于是
        // `ls && shutdown -h now` 的"动词"被判成 ls，灾难熔断完全没触发——
        // 而档位 3 的界面承诺是"灾难性操作仍会要求确认"。
        // 同理漏掉的还有 `nvidia-smi && mkfs.ext4 /dev/sda`、`df -h && mkfs...`。
        //
        // 注意 `ls && rm -rf /` 以前是被拦住的（rm 走 token 匹配、/ 在 CRITICAL_EXACT），
        // 漏的只有"基于动词"的那几类（shutdown / reboot / mkfs 等）。
        // 这里对每段都跑一次完整判定，两类都覆盖。
        val segments = splitBySeparators(normalized)
        if (segments.size > 1) {
            // 用同一个函数递归判定每一段。段内可能还有嵌套分隔符，
            // 递归会继续拆——直到每段都不含分隔符为止。
            if (segments.any { isCatastrophic(it) }) return true
            // v0.2.75：**同时**对整条命令做一次"删除关键路径"判定。
            //
            // 只做分段会漏掉"关键信息被拆开"的写法：`echo / | xargs rm -rf` 里
            // `/` 在第一段、`rm -rf` 在第二段，分段后两边各自都不完整，
            // 于是 v0.2.74 的分段版本**反而漏了这条**（v0.2.73 不分段时能拦住
            // —— 那时整条命令的 tokens 里 `/` 与 `rm` 同时出现）。
            // 这是分段引入的回归，这里补回来：整条命令再判一次。
            val allTokens = normalized.split(' ', '\t')
                .map { it.trim('"', '\'') }
                .filter { it.isNotBlank() }
            if (deletesCriticalPath(allTokens)) return true
            return false
        }

        // v0.2.75（安全修复）：token 统一**剥掉引号**再判定。
        //
        // 以前这里不剥，而 isCriticalTarget 剥（trim 引号）——同一份命令里两套口径。
        // 后果：`bash -c "rm -rf /"` 分词后是 [bash, -c, "rm, -rf, /"]，其中 "rm 不等于 rm，
        // 于是 deleting 判定为 false、灾难熔断完全不触发。同理漏掉 sh -c / eval / xargs。
        // （这是词法层的尽力而为：脚本语言里的语义逃逸挡不住，那种靠人工确认兜底。）
        val tokens = normalized.split(' ', '\t')
            .map { it.trim('"', '\'') }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val verb = commandVerb(tokens)
        if (verb in SHUTDOWN_TOKENS) return true
        if (verb == "mkfs" || verb?.startsWith("mkfs.") == true) return true
        if (tokens.any { it.startsWith("of=/dev/") && it !in SAFE_DEV_NODES }) return true

        val recursiveFlag = tokens.any { RECURSIVE_FLAG.matches(it) }
        val chmodLike = tokens.any { it == "chmod" || it == "chown" }
        if (chmodLike && recursiveFlag && tokens.any { isCriticalTarget(it) }) return true

        return deletesCriticalPath(tokens)
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
     * 这串 token 是否构成"递归删除关键路径"（v0.2.75 抽出来给两处共用）。
     *
     * 三个条件同时满足才算：① 有删除动词（rm/rmdir/shred/find -delete）
     * ② 有递归语义（-r/-rf/-fr，或 rmdir / find -delete 本身就是递归）
     * ③ 目标命中关键路径（`/`、`~`、`/usr` 等），或显式 --no-preserve-root。
     *
     * 抽成函数是因为「分段后的每一段」与「整条命令」都要用它——
     * 后者专治关键信息被分隔符拆开的情况（如 `echo / | xargs rm -rf`）。
     */
    private fun deletesCriticalPath(tokens: List<String>): Boolean {
        val deleting = tokens.any { it == "rm" || it == "rmdir" || it == "shred" } ||
            (tokens.any { it == "find" } && tokens.any { it == "-delete" || it == "-exec" })
        if (!deleting) return false
        // `rmdir`/`find -delete` 本身就是递归语义，不要求 -r 标记。
        val recursive = tokens.any { RECURSIVE_FLAG.matches(it) } ||
            tokens.any { it == "rmdir" || it == "-delete" || it == "-exec" }
        if (!recursive) return false
        if (tokens.any { it == "--no-preserve-root" }) return true
        return tokens.any { isCriticalTarget(it) }
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
