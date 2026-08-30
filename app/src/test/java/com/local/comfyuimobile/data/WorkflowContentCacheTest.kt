package com.local.comfyuimobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkflowContentCacheTest {

    @Before
    fun setUp() {
        WorkflowContentCache.clear()
    }

    @Test
    fun returnsNullWhenNothingCached() {
        assertNull(WorkflowContentCache[SERVER, "workflows/a.json"])
    }

    @Test
    fun roundTripsContentForSameServer() {
        WorkflowContentCache.put(SERVER, "workflows/a.json", "{\"nodes\":[]}")
        assertEquals("{\"nodes\":[]}", WorkflowContentCache[SERVER, "workflows/a.json"])
    }

    @Test
    fun isolatesDifferentServers() {
        WorkflowContentCache.put(SERVER, "workflows/a.json", "server-a")
        assertNull(WorkflowContentCache["https://other.example.com", "workflows/a.json"])
    }

    @Test
    fun ignoresUrlTrailingSlashAndCase() {
        WorkflowContentCache.put(SERVER, "workflows/a.json", "content")
        assertEquals("content", WorkflowContentCache["$SERVER/", "workflows/a.json"])
        // 同一台服务器的大小写写法应命中同一份缓存
        assertEquals("content", WorkflowContentCache[SERVER.uppercase(), "workflows/a.json"])
    }

    @Test
    fun replacingContentKeepsLatestValue() {
        WorkflowContentCache.put(SERVER, "workflows/a.json", "v1")
        WorkflowContentCache.put(SERVER, "workflows/a.json", "v2")
        assertEquals("v2", WorkflowContentCache[SERVER, "workflows/a.json"])
        assertEquals(1, WorkflowContentCache.size())
    }

    @Test
    fun overwriteDoesNotGrowEntryCount() {
        repeat(10) { WorkflowContentCache.put(SERVER, "workflows/a.json", "v$it") }
        assertEquals(1, WorkflowContentCache.size())
    }

    @Test
    fun evictsLeastRecentlyUsedBeyondCapacity() {
        val capacity = 24
        repeat(capacity) { index ->
            WorkflowContentCache.put(SERVER, "workflows/w$index.json", "content-$index")
        }
        assertEquals(capacity, WorkflowContentCache.size())
        // 再放一条，最早放进来的 w0 应该被淘汰
        WorkflowContentCache.put(SERVER, "workflows/new.json", "new")
        assertEquals(capacity, WorkflowContentCache.size())
        assertNull(WorkflowContentCache[SERVER, "workflows/w0.json"])
        assertEquals("new", WorkflowContentCache[SERVER, "workflows/new.json"])
    }

    @Test
    fun readingAnEntryRefreshesItsPosition() {
        val capacity = 24
        repeat(capacity) { index ->
            WorkflowContentCache.put(SERVER, "workflows/w$index.json", "content-$index")
        }
        // 读一次 w0，把它挪到队尾，它就不该是下一个被淘汰的
        assertEquals("content-0", WorkflowContentCache[SERVER, "workflows/w0.json"])
        WorkflowContentCache.put(SERVER, "workflows/new.json", "new")
        assertEquals("content-0", WorkflowContentCache[SERVER, "workflows/w0.json"])
        assertNull(WorkflowContentCache[SERVER, "workflows/w1.json"])
    }

    @Test
    fun removeDropsSingleEntry() {
        WorkflowContentCache.put(SERVER, "workflows/a.json", "a")
        WorkflowContentCache.put(SERVER, "workflows/b.json", "b")
        WorkflowContentCache.remove(SERVER, "workflows/a.json")
        assertNull(WorkflowContentCache[SERVER, "workflows/a.json"])
        assertEquals("b", WorkflowContentCache[SERVER, "workflows/b.json"])
        assertEquals(1, WorkflowContentCache.size())
    }

    @Test
    fun ignoresBlankPathOrContent() {
        WorkflowContentCache.put(SERVER, "", "content")
        WorkflowContentCache.put(SERVER, "workflows/a.json", "")
        assertEquals(0, WorkflowContentCache.size())
    }

    @Test
    fun clearEmptiesEverything() {
        WorkflowContentCache.put(SERVER, "workflows/a.json", "a")
        WorkflowContentCache.clear()
        assertEquals(0, WorkflowContentCache.size())
        assertTrue(WorkflowContentCache[SERVER, "workflows/a.json"] == null)
    }

    private companion object {
        const val SERVER = "https://aistudio.baidu.com/x/api_serving/8188"
    }
}
