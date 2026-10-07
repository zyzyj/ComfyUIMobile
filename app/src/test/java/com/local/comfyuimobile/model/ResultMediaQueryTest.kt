package com.local.comfyuimobile.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结果页搜索匹配单测（v0.2.99）。
 *
 * 断言先用 Python 复刻同一套 split/contains 跑过 11 个用例后才落成——
 * 搜索这种"看起来显然"的逻辑最容易在大小写、空串、多词上出错。
 */
class ResultMediaQueryTest {

    private fun media(
        filename: String = "cat_001.png",
        workflowName: String = "",
        workflowPath: String = "",
        nodeTitle: String = "",
        prompt: String? = null,
        seed: String? = null,
    ) = ResultMedia(
        jobId = "job",
        nodeId = "9",
        filename = filename,
        subfolder = "",
        type = "output",
        kind = MediaKind.IMAGE,
        url = "http://x/${filename}",
        workflowName = workflowName,
        workflowPath = workflowPath,
        nodeTitle = nodeTitle,
        positivePrompt = prompt,
        seed = seed,
    )

    @Test
    fun blankQueryMatchesEverything() {
        // 空查询不能变成"筛掉全部"——那会让结果页一打开就是空的。
        assertTrue(media().matchesQuery(""))
        assertTrue(media().matchesQuery("   "))
    }

    @Test
    fun matchesFilenameCaseInsensitively() {
        assertTrue(media().matchesQuery("cat"))
        assertTrue(media().matchesQuery("CAT"))
        assertFalse(media().matchesQuery("dog"))
    }

    @Test
    fun matchesAcrossFields() {
        assertTrue(media(workflowName = "anima_29b.json").matchesQuery("anima"))
        assertTrue(media(prompt = "1girl, solo").matchesQuery("1girl"))
        assertTrue(media(seed = "12345").matchesQuery("12345"))
        assertTrue(media(nodeTitle = "KSampler").matchesQuery("ksampler"))
        assertTrue(media(workflowPath = "workflows/x/anima.json").matchesQuery("workflows"))
    }

    @Test
    fun multipleTermsRequireAllToMatch() {
        // 多词是 AND：`anima 001` 要同时命中工作流与文件名，才能缩到目标。
        val m = media(filename = "cat_001.png", workflowName = "anima.json")
        assertTrue(m.matchesQuery("anima 001"))
        assertFalse(m.matchesQuery("anima 999"))
    }

    @Test
    fun missingOptionalFieldsDoNotCrash() {
        // prompt / seed 常为 null（云端历史里不一定带）——不能因此抛异常。
        assertFalse(media(prompt = null, seed = null).matchesQuery("1girl"))
    }
}
