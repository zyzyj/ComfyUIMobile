package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * v0.1.87：输出缓存 key 不能有歧义。
 *
 * 旧实现把五个字段用 "/" 直接拼接，而 ComfyUI 的 subfolder 自带日期斜杠，
 * 于是 `subfolder="a/b" + filename="c.png"` 与 `subfolder="a" + filename="b/c.png"`
 * 会撞成同一个 key——第二条输出直接覆盖第一条已下载的图片。
 */
class ResultKeyTest {

    @Test fun splitsAcrossFieldBoundariesDoNotCollide() {
        val first = ResultMedia(
            jobId = "job-1", nodeId = "9", type = "output",
            subfolder = "a/b", filename = "c.png", url = "u", kind = MediaKind.IMAGE,
        )
        val second = ResultMedia(
            jobId = "job-1", nodeId = "9", type = "output",
            subfolder = "a", filename = "b/c.png", url = "u", kind = MediaKind.IMAGE,
        )
        assertNotEquals("两种切分必须得到不同的 key", first.stableKey(), second.stableKey())
    }

    @Test fun oldSlashJoinWouldHaveCollided() {
        // 反过来证明旧编码确实会撞——这条断言是这次修复存在的理由。
        val joined = { subfolder: String, filename: String ->
            listOf("job-1", "9", "output", subfolder, filename).joinToString("/")
        }
        assertEquals(joined("a/b", "c.png"), joined("a", "b/c.png"))
    }

    @Test fun sameOutputAlwaysYieldsSameKey() {
        val media = ResultMedia(
            jobId = "job-1", nodeId = "9", type = "output",
            subfolder = "2026-09-01/ComfyUI_00001_", filename = "a.png", url = "u", kind = MediaKind.IMAGE,
        )
        assertEquals(media.stableKey(), media.copy().stableKey())
    }

    @Test fun differsByEveryField() {
        val base = ResultMedia(
            jobId = "job-1", nodeId = "9", type = "output",
            subfolder = "s", filename = "a.png", url = "u", kind = MediaKind.IMAGE,
        )
        val variants = listOf(
            base.copy(jobId = "job-2"),
            base.copy(nodeId = "10"),
            base.copy(type = "input"),
            base.copy(subfolder = "s2"),
            base.copy(filename = "b.png"),
        )
        variants.forEach { assertNotEquals(base.stableKey(), it.stableKey()) }
    }

    @Test fun emptyFieldsAreHandled() {
        val media = ResultMedia(url = "", kind = MediaKind.IMAGE, jobId = "", nodeId = "", type = "", subfolder = "", filename = "")
        assertEquals("0:|0:|0:|0:|0:|", media.stableKey())
    }
}
