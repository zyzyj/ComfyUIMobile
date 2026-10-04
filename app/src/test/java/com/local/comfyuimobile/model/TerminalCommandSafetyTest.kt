package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.54 AI 终端助手：命令安全判定与解析的单测。
 *
 * 这组测试是安全边界的锁：一旦规则被改坏（例如 `rm` 不再被识别为危险），
 * AI 提议的删除命令就会静默变成"一键执行"，后果是用户模型仓库被清空。
 */
class TerminalCommandSafetyTest {

    // ===== 危险判定 =====

    @Test
    fun flagsDestructiveCommands() {
        assertTrue(TerminalCommandSafety.isDangerous("rm -rf ~/models/loras"))
        assertTrue(TerminalCommandSafety.isDangerous("rm foo.txt"))
        assertTrue(TerminalCommandSafety.isDangerous("mv a.txt b.txt"))
        assertTrue(TerminalCommandSafety.isDangerous("pip uninstall torch"))
        assertTrue(TerminalCommandSafety.isDangerous("git reset --hard HEAD~1"))
        assertTrue(TerminalCommandSafety.isDangerous("chmod 777 /etc/passwd"))
        assertTrue(TerminalCommandSafety.isDangerous("apt install nvidia-driver"))
    }

    @Test
    fun flagsRedirectionAsDangerous() {
        // 覆盖写文件同样是不可逆操作
        assertTrue(TerminalCommandSafety.isDangerous("echo x > /etc/hosts"))
        assertTrue(TerminalCommandSafety.isDangerous("cat a >> b.txt"))
    }

    @Test
    fun readOnlyCommandsAreNotDangerous() {
        assertFalse(TerminalCommandSafety.isDangerous("ls -la ~/ComfyUI"))
        assertFalse(TerminalCommandSafety.isDangerous("du -sh ~/models"))
        assertFalse(TerminalCommandSafety.isDangerous("nvidia-smi"))
        assertFalse(TerminalCommandSafety.isDangerous("pip list"))
        assertFalse(TerminalCommandSafety.isDangerous("find ~/models -name \"*.safetensors\""))
        assertFalse(TerminalCommandSafety.isDangerous("grep -r error /tmp/comfyui.log"))
    }

    @Test
    fun findWithDeleteIsDangerousDespiteFindPrefix() {
        // find 是只读前缀，但 -delete / -exec 是写操作：不能因为前缀是 find 就放行。
        assertTrue(TerminalCommandSafety.isDangerous("find ~/models -name \"*.tmp\" -delete"))
        assertTrue(TerminalCommandSafety.isDangerous("find . -name x -exec rm {} \\;"))
        assertFalse(TerminalCommandSafety.isReadOnly("find ~/models -name \"*.tmp\" -delete"))
    }

    @Test
    fun wordBoundaryPreventsFalsePositives() {
        // "rm" 不能命中 "form" / "format"
        assertFalse(TerminalCommandSafety.isDangerous("echo format_disk_name"))
        assertFalse(TerminalCommandSafety.isDangerous("python -c \"print('confirmed')\""))
    }

    @Test
    fun pipelineWithWriteIsNotReadOnly() {
        assertFalse(TerminalCommandSafety.isReadOnly("cat a.txt | tee b.txt"))
        // 纯只读管道仍算只读
        assertTrue(TerminalCommandSafety.isReadOnly("cat a.txt | grep error"))
    }

    @Test
    fun installCommandsAreDetected() {
        assertTrue(TerminalCommandSafety.isInstall("pip install torch"))
        assertTrue(TerminalCommandSafety.isInstall("git clone https://github.com/x/y.git"))
        assertTrue(TerminalCommandSafety.isInstall("wget https://example.com/model.safetensors"))
        assertFalse(TerminalCommandSafety.isInstall("ls ~/models"))
    }

    // ===== 权限等级（v0.2.59） =====

    @Test
    fun level1RequiresConfirmationForEverything() {
        assertTrue(TerminalCommandSafety.requiresConfirmation("ls ~", TerminalCommandSafety.LEVEL_ASK_ALL))
        assertTrue(TerminalCommandSafety.requiresConfirmation("rm -rf x", TerminalCommandSafety.LEVEL_ASK_ALL))
    }

    @Test
    fun level2OnlyAsksForDangerous() {
        assertEquals(2, TerminalCommandSafety.LEVEL_ASK_DANGEROUS)
        assertFalse(TerminalCommandSafety.requiresConfirmation("ls ~/models", 2))
        assertFalse(TerminalCommandSafety.requiresConfirmation("nvidia-smi", 2))
        assertFalse(TerminalCommandSafety.requiresConfirmation("pip install torch", 2))
        assertTrue(TerminalCommandSafety.requiresConfirmation("rm -rf ~/models", 2))
        assertTrue(TerminalCommandSafety.requiresConfirmation("git reset --hard", 2))
    }

    @Test
    fun level3NeverAsks() {
        assertEquals(3, TerminalCommandSafety.LEVEL_ASK_NOTHING)
        assertFalse(TerminalCommandSafety.requiresConfirmation("ls ~", 3))
        // 3 级语义就是"不问"，危险命令也不问（用户自己选的）
        assertFalse(TerminalCommandSafety.requiresConfirmation("rm -rf ~/models", 3))
    }

    @Test
    fun levelIsClampedToValidRange() {
        // 越界值按边界处理，不会抛异常
        assertEquals(TerminalCommandSafety.LEVEL_ASK_ALL, 1)
        assertTrue(TerminalCommandSafety.requiresConfirmation("ls", 0))
        assertTrue(TerminalCommandSafety.requiresConfirmation("ls", -5))
        assertFalse(TerminalCommandSafety.requiresConfirmation("rm x", 99))
    }

    @Test
    fun levelLabelsAndDescriptionsCoverAllLevels() {
        (1..3).forEach { level ->
            assertTrue(TerminalCommandSafety.levelLabel(level).isNotBlank())
            assertTrue(TerminalCommandSafety.levelDescription(level).isNotBlank())
        }
    }

    // ===== 输出边界标记 =====

    @Test
    fun wrapsCommandWithBoundaryMarkers() {
        val wrapped = TerminalCommandSafety.wrap("ls ~", "abc123")
        assertTrue(wrapped.contains(TerminalCommandSafety.beginMarker("abc123")))
        assertTrue(wrapped.contains(TerminalCommandSafety.endMarker("abc123")))
        assertTrue(wrapped.contains("ls ~"))
    }

    @Test
    fun parsesExitCodeFromMarkerLine() {
        val token = "tok"
        assertEquals(
            0,
            TerminalCommandSafety.parseExitCode("__AI_tok_END__ rc=0", token),
        )
        assertEquals(
            2,
            TerminalCommandSafety.parseExitCode("__AI_tok_END__ rc=2", token),
        )
        assertNull(TerminalCommandSafety.parseExitCode("normal output line", token))
        // 别的 token 的结束标记不算数
        assertNull(TerminalCommandSafety.parseExitCode("__AI_other_END__ rc=0", token))
    }

    // ===== 命令解析 =====

    @Test
    fun parsesCommandsFromFencedBlock() {
        val reply = """
            先看看磁盘占用：
            ```sh
            du -sh ~/models
            ```
            然后清理临时文件：
            ```sh
            rm -rf /tmp/cache
            ```
        """.trimIndent()
        val commands = TerminalCommandSafety.parseCommands(reply)
        assertEquals(listOf("du -sh ~/models", "rm -rf /tmp/cache"), commands)
    }

    @Test
    fun skipsCommentLinesInsideCodeBlock() {
        // v0.2.78：`#` 开头的行不当命令。修复前 `removePrefix("#")` 想兼容 root 提示符，
        // 却把注释也放行——而中文的 isLetter() 为 true → 中文句子被当命令提取。
        val reply = """
            ```sh
            # 先看显存
            nvidia-smi
            # 再看进程
            ps aux
            ```
        """.trimIndent()
        assertEquals(listOf("nvidia-smi", "ps aux"), TerminalCommandSafety.parseCommands(reply))
    }

    @Test
    fun skipsPureCommentLine() {
        val reply = """
            ```sh
            # 这一步只是说明
            ```
        """.trimIndent()
        assertTrue(TerminalCommandSafety.parseCommands(reply).isEmpty())
    }

    @Test
    fun skipsNonAsciiLinesEvenWithoutHash() {
        // 中文说明文字（无论有没有 #）都不该被当命令——命令名必须是 ASCII。
        val reply = """
            ```sh
            先看显存占用再决定
            nvidia-smi
            ```
        """.trimIndent()
        assertEquals(listOf("nvidia-smi"), TerminalCommandSafety.parseCommands(reply))
    }

    // ===== 中文路径 / 注释不能误伤（v0.2.80）=====
    //
    // v0.2.78 对**整行**做非 ASCII 过滤，把 `ls ~/模型/loras`、`nvidia-smi  # 查看显存`
    // 这类合法命令一并丢弃（中文路径在云端很常见，守则本身是中文写的、模型爱加中文注释）。
    // 而 wrap 层的 sanitizeCommand 本来就能处理它们——解析层提前扔掉，两层能力没对齐。

    @Test
    fun keepsChinesePathAndFilename() {
        val reply = """
            ```sh
            ls ~/模型/loras
            cat ~/说明.txt
            ```
        """.trimIndent()
        assertEquals(
            listOf("ls ~/模型/loras", "cat ~/说明.txt"),
            TerminalCommandSafety.parseCommands(reply),
        )
    }

    @Test
    fun keepsChineseComment() {
        // 注释由 wrap 层的 sanitizeCommand 剥离，解析层不该提前丢弃整条命令。
        val reply = """
            ```sh
            nvidia-smi  # 查看显存
            df -h ~  # 看磁盘余量
            ```
        """.trimIndent()
        assertEquals(
            listOf("nvidia-smi  # 查看显存", "df -h ~  # 看磁盘余量"),
            TerminalCommandSafety.parseCommands(reply),
        )
    }

    @Test
    fun stillRejectsChineseProseAsCommandName() {
        // 放宽后仍必须拦住"中文说明句"——它们首 token 就是中文，不是命令。
        assertEquals(emptyList<String>(), TerminalCommandSafety.parseCommands("```sh\n检查磁盘\n```"))
        assertEquals(emptyList<String>(), TerminalCommandSafety.parseCommands("```sh\n模型/loras\n```"))
    }

    @Test
    fun doesNotTreatProseAsCommand() {
        // 普通句子不能被当成命令执行 —— 这是事故来源。
        val reply = "你可以试试看，ComfyUI 一般装在 home 目录下。"
        assertTrue(TerminalCommandSafety.parseCommands(reply).isEmpty())
    }

    @Test
    fun deduplicatesAndCapsCommands() {
        val many = (1..30).joinToString("\n") { "ls ~/dir$it" }
        val reply = "```sh\n$many\n```"
        val commands = TerminalCommandSafety.parseCommands(reply)
        assertEquals(TerminalCommandSafety.MAX_COMMANDS, commands.size)
    }

    // ===== 输出窗口切分（v0.2.61） =====

    @Test
    fun sliceOutputFindsBodyBetweenMarkers() {
        val token = "tok"
        val lines = listOf(
            "user@host:~$ echo __AI_tok_BEGIN__ && { ls ~ ; } ; echo __AI_tok_END__ rc=\$?",
            "__AI_tok_BEGIN__",
            "ComfyUI",
            "models",
            "__AI_tok_END__ rc=0",
        )
        val window = TerminalCommandSafety.sliceOutput(lines, token)
        assertTrue(window != null)
        assertEquals("ComfyUI\nmodels", window!!.output)
        assertEquals(0, window.exitCode)
    }

    @Test
    fun sliceOutputIgnoresOlderOutputFromSameBuffer() {
        // 终端缓冲里还留着上一次命令的输出与标记：绝不能误当本次结果。
        val token = "new"
        val lines = listOf(
            "__AI_old_BEGIN__",
            "旧的输出",
            "__AI_old_END__ rc=0",
            "user@host:~$ echo __AI_new_BEGIN__ && { ls ; } ; echo __AI_new_END__ rc=\$?",
            "__AI_new_BEGIN__",
            "新输出",
            "__AI_new_END__ rc=0",
        )
        val window = TerminalCommandSafety.sliceOutput(lines, token)!!
        assertEquals("新输出", window.output)
        assertFalse("旧输出不能混进来", window.output.contains("旧的输出"))
    }

    @Test
    fun sliceOutputSurvivesBufferTrim() {
        // 关键回归（v0.2.61）：缓冲裁剪后，用「发送前第几行」当起点会整体偏移、
        // 永远找不到结束标记（命令早跑完了还要白等满超时）。token 标记免疫裁剪。
        val token = "tok"
        val noise = (1..1_900).map { "填充行 $it" }
        val lines = buildList {
            add("很久以前的输出")
            addAll(noise) // 这之间 BEGIN 之前的内容全被裁掉
            add("user@host:~$ echo __AI_tok_BEGIN__ && { ls ; } ; echo __AI_tok_END__ rc=\$?")
            add("__AI_tok_BEGIN__")
            add("裁剪后的输出")
            add("__AI_tok_END__ rc=0")
        }
        val window = TerminalCommandSafety.sliceOutput(lines, token)
        assertTrue("缓冲裁剪后仍要能切出结果", window != null)
        assertEquals("裁剪后的输出", window!!.output)
    }

    @Test
    fun sliceOutputReturnsNullWhenOutputNotFinished() {
        val token = "tok"
        // 只有 BEGIN，END 还没到 → 不能当作完成
        assertNull(
            TerminalCommandSafety.sliceOutput(
                listOf("__AI_tok_BEGIN__", "输出到一半"),
                token,
            ),
        )
        // 回显都还没到 → 也不能当作完成
        assertNull(TerminalCommandSafety.sliceOutput(listOf("无关内容"), token))
    }

    @Test
    fun sliceOutputStripsMarkersFromBody() {
        val token = "tok"
        // shell 回显可能把 BEGIN 行也复述一遍，输出里不该带上标记文本
        val lines = listOf(
            "__AI_tok_BEGIN__",
            "__AI_tok_BEGIN__",
            "真正的输出",
            "__AI_tok_END__ rc=3",
        )
        val window = TerminalCommandSafety.sliceOutput(lines, token)!!
        assertFalse(window.output.contains("__AI_tok"))
        assertTrue(window.output.contains("真正的输出"))
        assertEquals(3, window.exitCode)
    }

    // ===== 连续失败计数（v0.2.71）=====

    @Test
    fun timeoutDoesNotCountAsFailure() {
        // 真机反馈：装依赖/下模型动辄十几分钟，超过 10 分钟等待上限很正常。
        // 以前超时被记成失败，连续 3 条慢命令就误报"已暂停自动执行"。
        assertEquals(0, TerminalCommandSafety.nextFailureCount(0, null))
        assertEquals(2, TerminalCommandSafety.nextFailureCount(2, null))
    }

    @Test
    fun successResetsFailureCount() {
        assertEquals(0, TerminalCommandSafety.nextFailureCount(0, 0))
        assertEquals(0, TerminalCommandSafety.nextFailureCount(5, 0))
    }

    @Test
    fun nonZeroExitIncrementsFailureCount() {
        assertEquals(1, TerminalCommandSafety.nextFailureCount(0, 1))
        assertEquals(3, TerminalCommandSafety.nextFailureCount(2, 127))
    }

    // ===== 复合命令（v0.2.75 补齐同类残留）=====
    //
    // 复审指出：v0.2.74 给 isReadOnly / isCatastrophic 加了分段，但 isInstall 漏了，
    // 而档位 2（默认）的放行条件里就有它。三类都是"同一根因只修了一半"。

    @Test
    fun installFollowedByArbitraryCommandIsNotAutoRunnable() {
        // 以安装/下载开头后面接什么都放行 = 档位 2 下免确认执行任意代码。
        // 其中 `git clone ... && cd ... && ./install.sh` 是装插件的标准写法，
        // 不是刻意构造的攻击。
        listOf(
            "wget https://x.com/install.sh && sh install.sh",
            "curl -L -o a.sh https://x/a.sh && sh a.sh",
            "git clone https://github.com/a/ComfyUI-X.git && cd ComfyUI-X && ./install.sh",
            "unzip plugin.zip && ./install",
            "npm install x && node ./postinstall.js",
            "wget https://x/m.py && python m.py",
        ).forEach { command ->
            assertFalse("安装开头 + 后续非只读，不能自动执行：$command", TerminalCommandSafety.autoRunnable(command, 2))
        }
    }

    @Test
    fun plainInstallCommandsStillPassWithoutConfirmation() {
        // 回归护栏：合规的安装/下载仍要放行（这是档位 2 存在的意义：装插件免确认）
        listOf(
            "pip install comfyui-manager",
            "git clone https://x/y.git",
            "wget https://x/m.safetensors",
            "unzip plugin.zip",
            "pip install x && ls ~",        // 后续段是只读 → 仍放行
        ).forEach { command ->
            assertTrue("合规安装应放行：$command", TerminalCommandSafety.autoRunnable(command, 2))
        }
    }

    @Test
    fun quotedDestructiveCommandsAreStillCatastrophic() {
        // 以前 tokenize 不剥引号，而 isCriticalTarget 剥——同一命令两套口径。
        // `bash -c "rm -rf /"` 分词后 `"rm` 不等于 `rm`，deleting 判定为 false，
        // 灾难熔断完全不触发。档位 3 的承诺是"灾难性操作仍会要求确认"。
        listOf(
            "bash -c \"rm -rf /\"",
            "sh -c \"rm -rf /\"",
            "eval \"rm -rf /\"",
        ).forEach { command ->
            assertTrue("带引号的递归删根仍是灾难：$command", TerminalCommandSafety.isCatastrophic(command))
            assertFalse("档位 3 也不放行：$command", TerminalCommandSafety.autoRunnable(command, 3))
        }
    }

    @Test
    fun criticalTargetSplitAcrossSegmentsIsStillCaught() {
        // 关键信息被分隔符拆到两段的情况：`/` 在第一段、`rm -rf` 在第二段。
        // 只做分段会漏（v0.2.74 的回归），所以整条命令还要再判一次。
        assertTrue(TerminalCommandSafety.isCatastrophic("echo / | xargs rm -rf"))
        assertFalse(TerminalCommandSafety.autoRunnable("echo / | xargs rm -rf", 3))
    }

    @Test
    fun processSubstitutionIsTreatedAsASeparator() {
        // `ls <(rm -rf x)`：子命令同样会执行，以前 <( 不在分隔符表里。
        listOf(
            "ls <(rm -rf ~/models)",
            "cat <(rm x)",
        ).forEach { command ->
            assertFalse("进程替换里的子命令也要分段判定：$command", TerminalCommandSafety.autoRunnable(command, 2))
        }
    }

    // ===== 复合命令不能绕过确认（v0.2.74 安全修复）=====
    //
    // 真漏洞：`isReadOnly` 以前只检查管道 `|`，漏掉 `&&` `||` `;` `$(` 反引号。
    // 于是 `ls && rm -rf ~/models` 因以只读命令开头被判成"只读"，
    // 而 isDangerous 对只读直接短路 → 默认档位（2）下**自动执行、不弹确认**。
    // 每一条都实测复现过（复现脚本见 RELEASE_NOTES）。

    @Test
    fun compoundCommandsWithDestructivePayloadAreNotReadOnly() {
        listOf(
            "ls && rm -rf ~/models",
            "ls ; shutdown -h now",
            "ls \$( rm -rf ~/models",
            "ls ` rm -rf ~/models",
            "cat x && rm -rf ~/models",
            "ls && mv ~/models /tmp",
            "ls && kill 1234",
            "nvidia-smi && mkfs.ext4 /dev/sda",
            "df -h && mkfs.ext4 /dev/sda",
            "ls && pip uninstall torch",
            // 单 `&`（后台执行）也要分段——这条是补完其他分隔符后做对抗性验证才发现的漏网，
            // 最初的报告里没列到：`ls & rm -rf x` 会让两件事都发生。
            "ls & rm -rf ~/models",
        ).forEach { command ->
            assertFalse("复合命令不能算只读：$command", TerminalCommandSafety.isReadOnly(command))
            assertFalse("复合命令不能自动执行：$command", TerminalCommandSafety.autoRunnable(command, 2))
            assertTrue("复合命令应被识别为危险：$command", TerminalCommandSafety.isDangerous(command))
        }
    }

    @Test
    fun compoundCommandStartingWithDangerousPartIsNotReadOnly() {
        // 反向也要挡住：以危险命令开头的复合命令同样是危险
        assertFalse(TerminalCommandSafety.isReadOnly("rm -rf x && ls"))
        assertFalse(TerminalCommandSafety.autoRunnable("rm -rf x && ls", 2))
    }

    @Test
    fun safeCompoundCommandsStillWork() {
        // 回归护栏：纯只读的复合命令不受影响——守则 v0.2.72 起明确允许"只读探查一次给两三条"，
        // 它们拼成一条时（`ls && du -sh`）仍然要能自动执行。
        listOf(
            "ls ~ && du -sh ~/models",
            "nvidia-smi ; df -h ~",
            "cat a.txt | grep error",
            "ls ~ | head -5",
        ).forEach { command ->
            assertTrue("纯只读复合命令仍应放行：$command", TerminalCommandSafety.autoRunnable(command, 2))
        }
    }

    @Test
    fun normalSingleCommandsBehaveAsBefore() {
        // 回归护栏：单条命令的行为一条都不能变（这是修复时最容易踩的地方）
        assertTrue(TerminalCommandSafety.isReadOnly("nvidia-smi 2>/dev/null"))
        assertTrue(TerminalCommandSafety.isReadOnly("find ~ -name \"*.safetensors\""))
        assertTrue(TerminalCommandSafety.autoRunnable("pip install comfyui-manager", 2))
        assertTrue(TerminalCommandSafety.autoRunnable("git clone https://x/y.git", 2))
        assertTrue(TerminalCommandSafety.autoRunnable("ls ~/ComfyUI/custom_nodes", 2))
        assertFalse(TerminalCommandSafety.autoRunnable("rm -rf ~/models", 2))
    }

    // ===== 灾难熔断要逐段判定（v0.2.74 安全修复）=====

    @Test
    fun compoundCommandsWithCatastrophicTailAreBlockedAtAnyLevel() {
        // 真漏洞：`isCatastrophic` 以前只取整条命令的首个 token 当动词，
        // 于是 `ls && shutdown -h now` 的"动词"被判成 ls，熔断完全不触发。
        // 而档位 3 的界面承诺是「灾难性操作仍会要求确认」。
        listOf(
            "ls && shutdown -h now",
            "nvidia-smi && shutdown -h now",
            "df -h && mkfs.ext4 /dev/sda",
            "ls && reboot",
            "df -h ; poweroff",
            "ls && rm -rf ~",
            "ls && rm -rf /usr/bin",
        ).forEach { command ->
            assertTrue("复合命令里的灾难动作必须触发熔断：$command", TerminalCommandSafety.isCatastrophic(command))
            assertFalse("熔断不受档位影响：$command", TerminalCommandSafety.autoRunnable(command, 3))
            assertFalse("档位 2 同样不放行：$command", TerminalCommandSafety.autoRunnable(command, 2))
        }
    }

    @Test
    fun catastrophicCompoundDoesNotFalsePositive() {
        // 回归护栏：不能因为分段判定把正常命令误报成灾难——
        // 误报会让用户对确认弹窗麻木，反而削弱安全边界。
        listOf(
            "nvidia-smi", "ls ~", "cat /etc/hosts", "df -h ~",
            "grep shutdown /var/log/x",      // 关键：shutdown 是参数不是动词
            "pip install comfyui-manager",
            "rm -rf ~/models/loras",         // 有明确子目录：危险但非灾难
            "chmod -R 755 ~/models",
            "echo hello",
        ).forEach { command ->
            assertFalse("不该误报为灾难：$command", TerminalCommandSafety.isCatastrophic(command))
        }
    }

    // ===== 自动执行白名单（v0.2.64）=====

    @Test
    fun levelTwoAutoRunsReadOnlyAndInstall() {
        // 用户抱怨的原话："不是危险才问的模式吗？为什么这种命令都要询问？"
        // （被问的是 `ls ~/ComfyUI/custom_nodes`）。这条锁住：只读命令在 2 级下自动执行。
        assertEquals(2, TerminalCommandSafety.LEVEL_ASK_DANGEROUS)
        assertTrue(TerminalCommandSafety.autoRunnable("ls ~/ComfyUI/custom_nodes", 2))
        assertTrue(TerminalCommandSafety.autoRunnable("nvidia-smi", 2))
        assertTrue(TerminalCommandSafety.autoRunnable("df -h ~", 2))
        assertTrue(TerminalCommandSafety.autoRunnable("pip install comfyui-manager", 2))
        assertTrue(TerminalCommandSafety.autoRunnable("git clone https://x/y.git", 2))
    }

    @Test
    fun levelTwoStillAsksForUnclassifiedCommands() {
        // 白名单语义：判不准的一律落回人工确认，而不是"不在危险名单里就放行"。
        // 反例是 `python -c "shutil.rmtree(...)"` 这类：既非只读也非危险，黑名单会漏放。
        assertFalse(TerminalCommandSafety.autoRunnable("python -c \"import shutil; shutil.rmtree('m')\"", 2))
        assertFalse(TerminalCommandSafety.autoRunnable("rm -rf ~/models", 2))
        assertFalse(TerminalCommandSafety.autoRunnable("mv a b", 2))
        assertFalse(TerminalCommandSafety.autoRunnable("./run.sh", 2))
    }

    @Test
    fun levelOneNeverAutoRuns() {
        assertFalse(TerminalCommandSafety.autoRunnable("ls ~", 1))
        assertFalse(TerminalCommandSafety.autoRunnable("nvidia-smi", 1))
    }

    @Test
    fun levelThreeAutoRunsEverythingExceptCatastrophic() {
        assertTrue(TerminalCommandSafety.autoRunnable("ls ~", 3))
        assertTrue(TerminalCommandSafety.autoRunnable("rm -rf ~/models/loras", 3))
        assertTrue(TerminalCommandSafety.autoRunnable("python -c \"x\"", 3))
        // 灾难性命令：任何档位都不自动执行
        assertFalse(TerminalCommandSafety.autoRunnable("rm -rf /", 3))
        assertFalse(TerminalCommandSafety.autoRunnable("reboot", 3))
    }

    @Test
    fun levelLabelsMatchRealBehaviour() {
        // 名字必须与放行行为一致——2 级原名「危险才问」时说一套做一套，
        // 用户看到 `ls` 也要手点就质疑了。
        assertEquals("每条都问", TerminalCommandSafety.levelLabel(1))
        assertEquals("只读与安装", TerminalCommandSafety.levelLabel(2))
        assertEquals("不问", TerminalCommandSafety.levelLabel(3))
        assertTrue(TerminalCommandSafety.levelDescription(2).contains("自动执行"))
    }

    // ===== 灾难性命令熔断（v0.2.62）=====

    @Test
    fun flagsCatastrophicDeletions() {
        assertTrue(TerminalCommandSafety.isCatastrophic("rm -rf /"))
        assertTrue(TerminalCommandSafety.isCatastrophic("rm -rf /*"))
        assertTrue(TerminalCommandSafety.isCatastrophic("rm -rf ~"))
        assertTrue(TerminalCommandSafety.isCatastrophic("rm -rf ~/*"))
        assertTrue(TerminalCommandSafety.isCatastrophic("sudo rm -rf /usr"))
        assertTrue(TerminalCommandSafety.isCatastrophic("rm -rf /usr/bin"))
        assertTrue(TerminalCommandSafety.isCatastrophic("rm -rf --no-preserve-root /"))
    }

    @Test
    fun catastrophicNeedsExplicitRecursion() {
        // 删单个文件不靠熔断拦（按危险命令确认就够了），否则会把用户逼成无脑点确认
        assertFalse(TerminalCommandSafety.isCatastrophic("rm ~/notes.txt"))
        assertFalse(TerminalCommandSafety.isCatastrophic("rm -rf ~/models/loras"))
        assertFalse(TerminalCommandSafety.isCatastrophic("ls -la ~/models"))
    }

    @Test
    fun flagsDiskAndBootHazards() {
        assertTrue(TerminalCommandSafety.isCatastrophic("mkfs.ext4 /dev/sda1"))
        assertTrue(TerminalCommandSafety.isCatastrophic("dd if=/dev/zero of=/dev/sda bs=1M"))
        assertTrue(TerminalCommandSafety.isCatastrophic("reboot"))
        assertTrue(TerminalCommandSafety.isCatastrophic("shutdown -h now"))
        assertTrue(TerminalCommandSafety.isCatastrophic(":(){ :|:& };:"))
    }

    @Test
    fun flagsRecursivePermissionChangesOnSystemPaths() {
        assertTrue(TerminalCommandSafety.isCatastrophic("chmod -R 777 /"))
        assertTrue(TerminalCommandSafety.isCatastrophic("chown -R user:user /etc"))
        // 改自己项目目录的权限不是灾难
        assertFalse(TerminalCommandSafety.isCatastrophic("chmod -R 755 ~/models"))
        assertFalse(TerminalCommandSafety.isCatastrophic("chmod +x run.sh"))
    }

    @Test
    fun catastrophicSurvivesLevelThree() {
        // 核心保证：即使用户选「不问」，灾难性命令也必须确认。
        assertTrue(TerminalCommandSafety.requiresConfirmation("rm -rf /", 3))
        assertTrue(TerminalCommandSafety.requiresConfirmation("reboot", 3))
        // 普通危险命令在 3 级下仍不要求确认（用户明确授予的权限）
        assertFalse(TerminalCommandSafety.requiresConfirmation("rm -rf ~/models/loras", 3))
        assertFalse(TerminalCommandSafety.requiresConfirmation("pip install torch", 3))
    }

    @Test
    fun catastrophicDoesNotFalsePositiveOnHarmlessCommands() {
        // 误报会让用户对确认窗口麻木，反而削弱安全边界。这些都该是安全命令。
        assertFalse(TerminalCommandSafety.isCatastrophic("nvidia-smi"))
        assertFalse(TerminalCommandSafety.isCatastrophic("ls -la ~"))
        assertFalse(TerminalCommandSafety.isCatastrophic("ls -la /usr/bin"))
        assertFalse(TerminalCommandSafety.isCatastrophic("cat /etc/hosts"))
        assertFalse(TerminalCommandSafety.isCatastrophic("df -h ~"))
    }

    @Test
    fun redirectToNullIsNotAWarning() {
        // `2>/dev/null` 极常见且无害：不能因此把探查命令也算成写操作。
        assertTrue(TerminalCommandSafety.isReadOnly("nvidia-smi 2>/dev/null"))
        assertFalse(TerminalCommandSafety.isCatastrophic("nvidia-smi 2>/dev/null"))
        assertTrue(TerminalCommandSafety.isReadOnly("ls ~ 2>/dev/null"))
    }

    @Test
    fun flagsWritesToSystemPaths() {
        // 覆盖写系统文件的威力不亚于删除，同样要靠熔断拦。
        assertTrue(TerminalCommandSafety.isCatastrophic("echo x > /etc/hosts"))
        assertTrue(TerminalCommandSafety.isCatastrophic("echo x >> /usr/local/bin/run"))
        assertTrue(TerminalCommandSafety.isCatastrophic("cat t.txt | tee /etc/profile"))
        // 写自己目录不是灾难
        assertFalse(TerminalCommandSafety.isCatastrophic("echo x > ~/notes.txt"))
        assertFalse(TerminalCommandSafety.isCatastrophic("echo x > /tmp/out.txt"))
    }

    // ===== 命令建议超限提示（v0.2.62）=====

    @Test
    fun parseAllCommandsReportsTrueTotal() {
        val many = (1..15).joinToString("\n") { "ls ~/dir$it" }
        val reply = "```sh\n$many\n```"
        // 截断版只给 MAX_COMMANDS 条；不截断版要给全，调用方才能算出被丢了几个。
        assertEquals(TerminalCommandSafety.MAX_COMMANDS, TerminalCommandSafety.parseCommands(reply).size)
        assertEquals(15, TerminalCommandSafety.parseAllCommands(reply).size)
    }

    // ===== 命令结果回喂格式 =====

    @Test
    fun commandResultFormatsForModel() {
        val result = TerminalCommandResult(
            command = "ls ~",
            output = "ComfyUI\nmodels",
            exitCode = 0,
            success = true,
        )
        val text = result.forModel()
        assertTrue(text.contains("$ ls ~"))
        assertTrue(text.contains("exit=0"))
        assertTrue(text.contains("ComfyUI"))
    }

    @Test
    fun commandResultTruncatesLongOutput() {
        val long = "x".repeat(5_000)
        val result = TerminalCommandResult("cmd", long, 0, true)
        val text = result.forModel(maxChars = 100)
        assertTrue("长输出必须被截断，否则会挤爆模型上下文", text.length < 500)
    }
}
