package com.local.comfyuimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v0.2.77：排队消息索引的 FIFO 语义。
 *
 * 这组测试锁的是 v0.2.76 的一个真 bug：同一个提问排队两次时，
 * `Map<文本, id>` 的第二次写入覆盖第一次，第一条占位消息永远删不掉，
 * UI 上留下右对齐的孤儿气泡、prompt 里同一句话仍出现两次。
 */
class QueuedMessageIndexTest {

    @Test
    fun `单次排队能取回对应 id`() {
        val index = QueuedMessageIndex()
        index.add("看下显存", "id1")
        assertEquals("id1", index.consume("看下显存"))
        assertNull("取出后再取应为空", index.consume("看下显存"))
    }

    @Test
    fun `同一句话排队两次按 FIFO 取回`() {
        // 用户等得不耐烦、或误触发送 —— 同一句话排两次
        val index = QueuedMessageIndex()
        index.add("看下显存", "id1")
        index.add("看下显存", "id2")
        assertEquals("先排队的先取出", "id1", index.consume("看下显存"))
        assertEquals("再取出第二条", "id2", index.consume("看下显存"))
        assertNull(index.consume("看下显存"))
    }

    @Test
    fun `不同提问互不干扰`() {
        val index = QueuedMessageIndex()
        index.add("看下显存", "a")
        index.add("装个插件", "b")
        assertEquals("b", index.consume("装个插件"))
        assertEquals("a", index.consume("看下显存"))
    }

    @Test
    fun `清空后取不到任何 id`() {
        val index = QueuedMessageIndex()
        index.add("看下显存", "id1")
        index.add("看下显存", "id2")
        index.clear()
        assertNull(index.consume("看下显存"))
    }

    @Test
    fun `未记录的提问返回空`() {
        val index = QueuedMessageIndex()
        assertNull(index.consume("没排过队"))
    }
}
