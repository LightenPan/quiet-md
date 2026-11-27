package com.zhaoolee.notes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncStateStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun saves_and_loads_round_trip() {
        val dir = temporary.newFolder()
        val store = SyncStateStore(dir)
        val state = SyncState(
            server = "https://dav.example/dav/",
            username = "me@example.com",
            remoteRoot = "Quiet.md",
            scanComplete = true,
            files = linkedMapOf("Work/a.md" to SyncFileState("\"e\"", 2, null, "abc")),
            folders = linkedSetOf("Work")
        )
        store.save(state)

        val loaded = store.load()
        assertNull(loaded.damagedPath)
        assertEquals(state, loaded.state)
    }

    @Test
    fun corrupt_json_is_quarantined() {
        val dir = temporary.newFolder()
        val store = SyncStateStore(dir)
        store.stateFile.parentFile?.mkdirs()
        store.stateFile.writeText("{not json", Charsets.UTF_8)

        val loaded = store.load()
        assertNotNull(loaded.damagedPath)
        assertTrue(loaded.damagedPath!!.isFile)
        assertEquals(SyncState(), loaded.state)
        assertTrue(File(dir, loaded.damagedPath!!.name).isFile)
    }

    @Test(expected = SyncException::class)
    fun newer_state_version_is_rejected_without_quarantine() {
        val dir = temporary.newFolder()
        val store = SyncStateStore(dir)
        store.stateFile.parentFile?.mkdirs()
        store.stateFile.writeText("""{"v":2}""", Charsets.UTF_8)
        try {
            store.load()
        } finally {
            assertTrue(store.stateFile.isFile)
            assertNull(dir.listFiles()?.firstOrNull { it.name.contains("corrupt") })
        }
    }
}
