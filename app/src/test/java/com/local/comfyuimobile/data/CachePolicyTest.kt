package com.local.comfyuimobile.data

import com.local.comfyuimobile.model.CacheOutputRule
import com.local.comfyuimobile.model.MediaKind
import com.local.comfyuimobile.model.ResultMedia
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachePolicyTest {
    private val rule = CacheOutputRule(
        serverUrl = "http://192.168.10.109:8188",
        workflowPath = "workflows/a.json",
        workflowName = "a.json",
        nodeId = "9",
        nodeTitle = "保存图像",
        nodeType = "SaveImage",
    )
    private val media = ResultMedia(
        jobId = "app-job",
        nodeId = "9",
        nodeType = "SaveImage",
        nodeTitle = "保存图像",
        filename = "a.png",
        subfolder = "",
        type = "output",
        kind = MediaKind.IMAGE,
        url = "http://server/view",
        workflowPath = "workflows/a.json",
    )

    @Test fun acceptsOnlyAppSubmittedMatchingOutput() {
        assertTrue(CachePolicy.shouldCache(media, setOf("app-job"), listOf(rule), rule.serverUrl))
        assertFalse(CachePolicy.shouldCache(media, emptySet(), listOf(rule), rule.serverUrl))
    }

    @Test fun appliesSameOutputTypeAcrossWorkflowsButRejectsOtherTypeOrServer() {
        assertTrue(CachePolicy.shouldCache(media.copy(nodeId = "10"), setOf("app-job"), listOf(rule), rule.serverUrl))
        assertTrue(CachePolicy.shouldCache(media.copy(workflowPath = "workflows/b.json"), setOf("app-job"), listOf(rule), rule.serverUrl))
        assertFalse(CachePolicy.shouldCache(media.copy(nodeType = "PreviewImage"), setOf("app-job"), listOf(rule), rule.serverUrl))
        assertFalse(CachePolicy.shouldCache(media, setOf("app-job"), listOf(rule), "http://192.168.10.110:8188"))
    }

    @Test fun clearedCacheDoesNotDownloadOldHistoryAgain() {
        val old = media.copy(createdAt = 1_000L)
        val new = media.copy(jobId = "new-job", createdAt = 3_000L)

        assertFalse(CachePolicy.shouldCache(old, setOf("app-job"), listOf(rule), rule.serverUrl, cacheClearedAt = 2_000L))
        assertTrue(CachePolicy.shouldCache(new, setOf("new-job"), listOf(rule), rule.serverUrl, cacheClearedAt = 2_000L))
    }

    @Test fun keepsEveryEligibleImageFromOneBatch() {
        val batch = (1..4).map { index -> media.copy(filename = "batch_$index.png", createdAt = 3_000L) }

        val eligible = batch.filter {
            CachePolicy.shouldCache(it, setOf("app-job"), listOf(rule), rule.serverUrl, cacheClearedAt = 2_000L)
        }

        assertTrue(eligible.size == 4)
    }

    @Test fun detectsWhetherCurrentWorkflowHasConfiguredLocalOutput() {
        assertTrue(CachePolicy.hasConfiguredOutput(listOf(rule), rule.serverUrl, setOf("SaveImage")))
        assertFalse(CachePolicy.hasConfiguredOutput(listOf(rule), rule.serverUrl, setOf("PreviewImage")))
        assertFalse(CachePolicy.hasConfiguredOutput(listOf(rule.copy(enabled = false)), rule.serverUrl, setOf("SaveImage")))
        assertFalse(CachePolicy.hasConfiguredOutput(listOf(rule), "http://other:8188", setOf("SaveImage")))
        assertFalse(CachePolicy.hasConfiguredOutput(listOf(rule), null, setOf("SaveImage")))
    }

    // ---------- v0.1.86：没配过规则时默认全缓存 ----------

    @Test fun cachesEverythingWhenUserNeverConfiguredAnyRule() {
        // 现场：用户开了"自动保存"却一张都存不下来，因为自动保存搭在输出白名单之上，
        // 而白名单要另外单独配置——日志里每一个"后台任务完成"都是"总输出=0"。
        assertTrue(CachePolicy.shouldCache(media, setOf("app-job"), emptyList(), rule.serverUrl))
        // 只要用户在任何服务器上配过规则，就严格按规则走（对老用户零回归）：
        // 别的服务器的规则不会让这台服务器"默认全存"。
        val otherServerRule = rule.copy(serverUrl = "http://192.168.10.110:8188")
        assertFalse(CachePolicy.shouldCache(media, setOf("app-job"), listOf(otherServerRule), rule.serverUrl))
    }

    @Test fun respectsDisabledRulesOnceAnyRuleExists() {
        // 用户一旦配了规则（哪怕是关掉的），就完全按规则走——主动关掉不该被"默认全存"覆盖。
        val disabled = rule.copy(enabled = false)
        assertFalse(CachePolicy.shouldCache(media, setOf("app-job"), listOf(disabled), rule.serverUrl))
    }

    @Test fun defaultCacheAllCanBeTurnedOff() {
        // 保留显式关闭的口子（例如某些场景只想严格按规则来）。
        assertFalse(
            CachePolicy.shouldCache(
                media, setOf("app-job"), emptyList(), rule.serverUrl, defaultCacheAll = false,
            )
        )
    }
}
