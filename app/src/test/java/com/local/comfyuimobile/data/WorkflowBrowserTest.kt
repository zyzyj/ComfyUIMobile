package com.local.comfyuimobile.data

import com.local.comfyuimobile.model.WorkflowEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowBrowserTest {
    private val entries = listOf(
        WorkflowEntry("KREA2", "workflows/KREA2", true),
        WorkflowEntry("SDXL", "workflows/SDXL", true),
        WorkflowEntry("root.json", "workflows/root.json", false),
        WorkflowEntry("krea.json", "workflows/KREA2/krea.json", false),
        WorkflowEntry("nested", "workflows/KREA2/nested", true),
        WorkflowEntry("deep.json", "workflows/KREA2/nested/deep.json", false),
    )

    @Test fun rootShowsOnlyRootFoldersAndFiles() {
        assertEquals(
            setOf("workflows/KREA2", "workflows/SDXL", "workflows/root.json"),
            WorkflowBrowser.entries(entries, WorkflowBrowser.ROOT, "").map { it.path }.toSet(),
        )
    }

    @Test fun folderShowsOnlyDirectChildren() {
        assertEquals(
            setOf("workflows/KREA2/krea.json", "workflows/KREA2/nested"),
            WorkflowBrowser.entries(entries, "workflows/KREA2", "").map { it.path }.toSet(),
        )
    }

    @Test fun searchFindsAcrossAllFoldersAndUpHandlesNestedFolders() {
        assertEquals(listOf("workflows/KREA2/nested/deep.json"), WorkflowBrowser.entries(entries, WorkflowBrowser.ROOT, "deep").map { it.path })
        assertEquals("workflows/KREA2", WorkflowBrowser.up("workflows/KREA2/nested"))
        assertEquals(WorkflowBrowser.ROOT, WorkflowBrowser.up("workflows/KREA2"))
    }

    @Test fun pathWithoutSlashBelongsToRoot() {
        // v0.1.87：以前返回空串，跟任何 folder 都比不上，这类工作流在浏览模式里不可见。
        assertEquals(WorkflowBrowser.ROOT, WorkflowBrowser.parent("loose.json"))
        assertEquals(WorkflowBrowser.ROOT, WorkflowBrowser.parent("workflows"))
        assertEquals("workflows", WorkflowBrowser.parent("workflows/root.json"))
        // 于是顶层散装工作流也能在根目录里列出来
        val withLoose = entries + WorkflowEntry("loose.json", "loose.json", false)
        assertTrue(
            WorkflowBrowser.entries(withLoose, WorkflowBrowser.ROOT, "").any { it.path == "loose.json" },
        )
    }
}
