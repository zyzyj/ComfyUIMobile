package com.local.comfyuimobile.model

/**
 * 排队提问的「文本 → 占位消息 id」索引（v0.2.77）。
 *
 * 终端助手在"上一条还在等回复"时会把新提问先放进消息列表占位，正式发送时再把
 * 占位那条移除——这个索引记录的就是"哪条占位消息属于哪句提问"。
 *
 * 同一句话可能被排队多次（用户等得不耐烦、或误触发送），所以每个文本对应一个
 * **FIFO 队列**而不是单个 id。v0.2.76 用的是 `Map<String, String>`：第二次排队
 * 覆盖第一次的 id，第一条占位消息永远删不掉，UI 上留下右对齐的孤儿气泡。
 * 两个数据结构描述同一件事、形状却不同（List 能存重复、Map 不能），边界上必然丢信息。
 */
class QueuedMessageIndex {

    private val index = mutableMapOf<String, ArrayDeque<String>>()

    /** 记一条排队消息的占位 id。 */
    fun add(prompt: String, messageId: String) {
        index.getOrPut(prompt) { ArrayDeque() }.addLast(messageId)
    }

    /** 取出该提问**最早**的一条占位 id 并移除；没有对应记录时返回 null。 */
    fun consume(prompt: String): String? {
        val ids = index[prompt] ?: return null
        val id = ids.removeFirstOrNull()
        if (ids.isEmpty()) index.remove(prompt)
        return id
    }

    /** 清空全部记录（清空对话 / 停止助手时用）。 */
    fun clear() {
        index.clear()
    }
}
