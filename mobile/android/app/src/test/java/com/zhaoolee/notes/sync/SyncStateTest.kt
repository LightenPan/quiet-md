package com.zhaoolee.notes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncStateTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun state_round_trips_resume_fields() {
        val store = SyncStateStore(temporary.newFolder("state"))
        store.save(
            SyncState(
                scanComplete = false,
                initialPolicy = InitialPolicy.MERGE_BOTH.name,
                files = linkedMapOf(
                    "a.md" to SyncFileState(etag = "\"e1\"", sha256 = "local", pendingUpload = true)
                )
            )
        )

        val loaded = store.load().state

        assertEquals(InitialPolicy.MERGE_BOTH.name, loaded.initialPolicy)
        assertFalse(loaded.scanComplete)
        assertTrue(loaded.files.getValue("a.md").pendingUpload)
    }

    @Test
    fun older_state_without_resume_fields_still_loads() {
        val dir = temporary.newFolder("old-state")
        java.io.File(dir, "state.json").writeText(
            """{"v":1,"server":"https://dav.example/dav/","username":"me","remoteRoot":"Quiet.md","scanComplete":true,"files":{"a.md":{"etag":"\"e1\"","size":1,"sha256":"h"}},"folders":[]}""",
            Charsets.UTF_8
        )

        val loaded = SyncStateStore(dir).load().state

        assertTrue(loaded.scanComplete)
        assertNull(loaded.initialPolicy)
        assertFalse(loaded.files.getValue("a.md").pendingUpload)
    }
}
