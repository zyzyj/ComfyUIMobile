package com.local.comfyuimobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
class WorkflowSnapshotStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): WorkflowSnapshotStore = WorkflowSnapshotStore(tmp.newFolder("snapshots"))

    @Test fun writeThenReadRoundTripsContent() {
        val s = store()
        val json = """{"nodes":[],"links":[]}"""
        kotlinx.coroutines.runBlocking { s.write("https://a.example/", "workflows/x.json", json) }
        assertEquals(json, kotlinx.coroutines.runBlocking { s.read("https://a.example/", "workflows/x.json") })
    }

    @Test fun readIsServerScoped() {
        val s = store()
        kotlinx.coroutines.runBlocking {
            s.write("https://a.example", "workflows/x.json", "A")
            s.write("https://b.example", "workflows/x.json", "B")
        }
        assertEquals("B", kotlinx.coroutines.runBlocking { s.read("https://B.example/", "workflows/x.json") })
        assertNull(kotlinx.coroutines.runBlocking { s.read("https://c.example", "workflows/x.json") })
    }

    // ===== v0.2.67：未连接时删除要先能定位到 @local 域 =====
    //
    // 真机反馈"没连上 comfyui 前没办法删除工作流"。根因是删除路径取了服务器域
    // （未连接时为空）就直接 return；而导入在未连接时写的是 @local 域。
    // 下面两条锁住这个映射关系：作用域为空 → @local；两个域各自独立可删。

    @Test fun blankScopeMapsToLocalScope() {
        // 与 MainViewModel.snapshotScopeFor 同一规则：空地址用统一的本机域
        fun scopeFor(serverUrl: String?) = serverUrl.orEmpty().ifBlank { WorkflowSnapshotStore.LOCAL_SCOPE }

        assertEquals(WorkflowSnapshotStore.LOCAL_SCOPE, scopeFor(null))
        assertEquals(WorkflowSnapshotStore.LOCAL_SCOPE, scopeFor(""))
        assertEquals("https://a.example", scopeFor("https://a.example"))
    }

    @Test fun removingLocalScopeLeavesServerScopeIntact() {
        // 删除要**分别**作用于两个域：只删 @local 不能碰服务器域缓存，
        // 反之亦然——否则未连接删一次会把连过服务器时的缓存也抹掉。
        val s = store()
        kotlinx.coroutines.runBlocking {
            s.write(WorkflowSnapshotStore.LOCAL_SCOPE, "workflows/x.json", "local-copy")
            s.write("https://a.example", "workflows/x.json", "server-copy")

            s.remove(WorkflowSnapshotStore.LOCAL_SCOPE, "workflows/x.json")

            assertNull(s.read(WorkflowSnapshotStore.LOCAL_SCOPE, "workflows/x.json"))
            assertEquals("server-copy", s.read("https://a.example", "workflows/x.json"))
        }
    }

    @Test fun removingBothScopesClearsEveryCopy() {
        // MainViewModel 删除时会遍历 [服务器域, @local] 两个作用域——这样
        // "连过服务器时缓存过、之后未连接又读到" 的工作流也能被清干净。
        val s = store()
        kotlinx.coroutines.runBlocking {
            s.write(WorkflowSnapshotStore.LOCAL_SCOPE, "workflows/x.json", "local-copy")
            s.write("https://a.example", "workflows/x.json", "server-copy")

            listOf("https://a.example", WorkflowSnapshotStore.LOCAL_SCOPE).forEach { scope ->
                s.remove(scope, "workflows/x.json")
            }

            assertNull(s.read(WorkflowSnapshotStore.LOCAL_SCOPE, "workflows/x.json"))
            assertNull(s.read("https://a.example", "workflows/x.json"))
            assertTrue(
                "两个域都清空后列表里不该再出现它",
                s.list(WorkflowSnapshotStore.LOCAL_SCOPE).none { it.path == "workflows/x.json" } &&
                    s.list("https://a.example").none { it.path == "workflows/x.json" },
            )
        }
    }

    @Test fun writeIsAtomicAndOverwrites() {
        val s = store()
        kotlinx.coroutines.runBlocking {
            s.write("https://a.example", "workflows/x.json", "old")
            s.write("https://a.example", "workflows/x.json", "new")
        }
        assertEquals("new", kotlinx.coroutines.runBlocking { s.read("https://a.example", "workflows/x.json") })
        // 临时文件不能残留，否则 list 会被垃圾文件污染
        assertEquals(1, tmp.root.resolve("snapshots").listFiles()!!.size)
    }

    @Test fun listOnlyReturnsThisServerSortedByNewestFirst() {
        val s = store()
        kotlinx.coroutines.runBlocking {
            s.write("https://a.example", "workflows/old.json", "A")
            Thread.sleep(15)
            s.write("https://a.example", "workflows/new.json", "B")
            s.write("https://b.example", "workflows/other.json", "C")
        }
        val entries = kotlinx.coroutines.runBlocking { s.list("https://A.example/") }
        assertEquals(listOf("new.json", "old.json"), entries.map { it.name })
        entries.forEach {
            assertTrue(it.path.startsWith("workflows/"))
            assertEquals(false, it.isDirectory)
        }
    }

    @Test fun listIgnoresCorruptedFiles() {
        val s = store()
        // store() 已把目录建好，直接往里塞一个写坏的文件
        tmp.root.resolve("snapshots/garbage.json").writeText("not-json{{{")
        kotlinx.coroutines.runBlocking { s.write("https://a.example", "workflows/x.json", "A") }
        val entries = kotlinx.coroutines.runBlocking { s.list("https://a.example") }
        assertEquals(listOf("workflows/x.json"), entries.map { it.path })
    }

    @Test fun removeDropsTheEntry() {
        val s = store()
        kotlinx.coroutines.runBlocking {
            s.write("https://a.example", "workflows/x.json", "A")
            s.remove("https://a.example", "workflows/x.json")
        }
        assertNull(kotlinx.coroutines.runBlocking { s.read("https://a.example", "workflows/x.json") })
        assertTrue(kotlinx.coroutines.runBlocking { s.list("https://a.example") }.isEmpty())
    }

    @Test fun blankInputsAreNoOps() {
        val s = store()
        kotlinx.coroutines.runBlocking {
            s.write("", "workflows/x.json", "A")
            s.write("https://a.example", "", "A")
            s.write("https://a.example", "workflows/x.json", "")
        }
        assertTrue(kotlinx.coroutines.runBlocking { s.list("https://a.example") }.isEmpty())
    }

    @Test fun pruneKeepsOnlyTheNewestMax() {
        val s = WorkflowSnapshotStore(tmp.newFolder("snapshots"))
        kotlinx.coroutines.runBlocking {
            repeat(WorkflowSnapshotStore.MAX_SNAPSHOTS + 5) { index ->
                s.write("https://a.example", "workflows/w$index.json", "A$index")
                Thread.sleep(2)
            }
        }
        val entries = kotlinx.coroutines.runBlocking { s.list("https://a.example") }
        assertEquals(WorkflowSnapshotStore.MAX_SNAPSHOTS, entries.size)
        // 最旧的 5 个应该被剪掉
        assertEquals(false, entries.any { it.path == "workflows/w0.json" })
        assertEquals(true, entries.any { it.path == "workflows/w${WorkflowSnapshotStore.MAX_SNAPSHOTS + 4}.json" })
    }

    @Test fun identityMatchesDraftStoreSemantics() {
        // 与 WorkflowDraftStore.identity 同一套哈希口径（server 归一化 + \n + path），
        // 两个存储永远不会互相串文件。
        val a = WorkflowSnapshotStore.identity("https://A.example/", "workflows/x.json")
        val b = WorkflowSnapshotStore.identity("https://a.example", "workflows/x.json")
        assertEquals(a, b)
        assertEquals(64, a.length)
    }
}
