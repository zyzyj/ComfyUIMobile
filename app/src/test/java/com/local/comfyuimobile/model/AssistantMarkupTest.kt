package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.63：AI 回复排版解析的单测。
 *
 * 锁住的是**用户直接看到的观感**：以前回复里的 ```sh 围栏与 `**加粗**` 星号
 * 原样露在界面上（真机截图可见），这组测试保证它们被正确切块与去标记。
 */
class AssistantMarkupTest {

    // ===== 切块 =====

    @Test
    fun splitsTextAndCodeBlock() {
        val reply = """
            先看下显存占用：

            ```sh
            nvidia-smi
            ```
        """.trimIndent()
        val blocks = AssistantMarkup.parse(reply)
        assertEquals(2, blocks.size)
        assertEquals("先看下显存占用：", blocks[0].text)
        assertFalse(blocks[0].isCode)
        assertEquals("nvidia-smi", blocks[1].text)
        assertTrue(blocks[1].isCode)
        assertEquals("sh", blocks[1].language)
    }

    @Test
    fun stripsFenceEvenWhenModelForgetsClosingFence() {
        // 模型偶尔漏写收尾围栏：内容仍要显示出来，不能整段丢掉
        val reply = "跑这个：\n\n```sh\ndf -h ~"
        val blocks = AssistantMarkup.parse(reply)
        assertEquals(2, blocks.size)
        assertEquals("df -h ~", blocks[1].text)
        assertTrue(blocks[1].isCode)
    }

    @Test
    fun handlesMultipleCodeBlocks() {
        val reply = "第一步：\n\n```sh\nls ~\n```\n\n第二步：\n\n```sh\ndf -h ~\n```\n"
        val blocks = AssistantMarkup.parse(reply)
        assertEquals(4, blocks.size)
        assertEquals(listOf(false, true, false, true), blocks.map { it.isCode })
        assertEquals("ls ~", blocks[1].text)
        assertEquals("df -h ~", blocks[3].text)
    }

    @Test
    fun textWithoutFenceIsSinglePlainBlock() {
        val blocks = AssistantMarkup.parse("磁盘还余 42G，够下这个模型。")
        assertEquals(1, blocks.size)
        assertFalse(blocks[0].isCode)
    }

    @Test
    fun blankInputProducesNoBlocks() {
        assertTrue(AssistantMarkup.parse("").isEmpty())
        assertTrue(AssistantMarkup.parse("   \n\n  ").isEmpty())
    }

    // ===== 去标记 =====

    @Test
    fun removesBoldMarkersButKeepsText() {
        // 截图里的原样：`**只读**` 曾连星号一起显示
        assertEquals("先跑几条只读命令", AssistantMarkup.plain("先跑几条**只读**命令"))
        assertEquals("A B", AssistantMarkup.plain("**A** **B**"))
    }

    @Test
    fun keepsLoneAsteriskForGlobs() {
        // 单个星号在命令里有意义，误删会让人误读命令
        assertEquals("rm -rf *", AssistantMarkup.plain("rm -rf *"))
        assertEquals("ls *.safetensors", AssistantMarkup.plain("ls *.safetensors"))
        // 落单的成对标记也不吃掉后半段
        assertEquals("注意 **这里没有闭合", AssistantMarkup.plain("注意 **这里没有闭合"))
    }

    @Test
    fun removesInlineCodeBackticks() {
        assertEquals("用 nvidia-smi 看显存", AssistantMarkup.plain("用 `nvidia-smi` 看显存"))
    }

    @Test
    fun plainTextInCodeBlocksIsPreservedVerbatim() {
        // 代码块内不做任何标记处理：命令必须原样
        val reply = "```sh\nps aux | grep -i \"[c]omfy\"\n```"
        val blocks = AssistantMarkup.parse(reply)
        assertEquals("ps aux | grep -i \"[c]omfy\"", blocks[0].text)
    }
}
