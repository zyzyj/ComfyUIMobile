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
