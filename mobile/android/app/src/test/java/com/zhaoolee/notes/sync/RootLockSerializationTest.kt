package com.zhaoolee.notes.sync

import com.zhaoolee.notes.library.LibraryRootLock
import com.zhaoolee.notes.library.NoteLibrary
import java.io.File
import java.time.Clock
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootLockSerializationTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun canonicalSpellingsOfTheSameRootShareOneMutex() {
        val root = temporary.newFolder("Quiet.md")
        val alias = File(root.parentFile, "${root.name}${File.separator}.")
        assertSame(LibraryRootLock.mutexFor(root), LibraryRootLock.mutexFor(alias))
    }

    @Test
    fun saveAndReadFromAnotherLibraryWaitUntilSyncLeavesTheRoot() = runBlocking {
        val root = temporary.newFolder("library")
        val stateDir = File(temporary.newFolder("state"), "nutstore")
        val scanEntered = CountDownLatch(1)
        val releaseScan = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val fileSystem = object : SyncFileSystem {
            override fun scan(): LocalScan {
                events.add("sync:scan")
                scanEntered.countDown()
                check(releaseScan.await(10, TimeUnit.SECONDS)) { "sync scan timed out" }
                events.add("sync:leave")
                return LocalScan()
            }

            override fun read(rel: String): ByteArray = error("unexpected read")
            override fun writeAtomic(rel: String, bytes: ByteArray) = error("unexpected write")
            override fun exists(rel: String): Boolean = false
            override fun isFile(rel: String): Boolean = false
            override fun isDirectory(rel: String): Boolean = false
            override fun ensureDirectory(rel: String) = Unit
            override fun moveFile(from: String, to: String) = error("unexpected move")
            override fun removeFile(rel: String) = error("unexpected remove")
            override fun removeEmptyDirectory(rel: String) = Unit
            override fun quarantine(rel: String): Boolean = false
        }
        val engine = NutstoreSyncEngine(
            SyncOptions(
                localRoot = root,
                stateDir = stateDir,
                config = WebDavConfig.create(
                    server = "https://dav.example/dav/",
                    username = "me@example.com",
                    remoteRoot = "Quiet.md"
                ),
                deviceName = "Android",
                clock = Clock.systemUTC()
            ),
            fileSystem = fileSystem
        )
        val secondLibrary = NoteLibrary(
            root,
            File(temporary.root, "second-library-meta.json")
        )
        val syncJob = launch(Dispatchers.IO) { engine.sync(FakeWebDav()) }
        val saveAttempted = CountDownLatch(1)
        lateinit var saveJob: Job
        lateinit var readJob: Job
        try {
            assertTrue(scanEntered.await(5, TimeUnit.SECONDS))
            saveJob = launch(Dispatchers.IO) {
                saveAttempted.countDown()
                events.add("save:before")
                secondLibrary.create("blocked", "", "body")
                events.add("save:done")
            }
            readJob = launch(Dispatchers.IO) {
                events.add("read:before")
                secondLibrary.list()
                events.add("read:done")
            }
            assertTrue(saveAttempted.await(5, TimeUnit.SECONDS))
            assertNull(withTimeoutOrNull(500) { saveJob.join() })
            assertNull(withTimeoutOrNull(500) { readJob.join() })
            assertFalse(events.contains("save:done"))
            assertFalse(events.contains("read:done"))
            assertFalse(File(root, "blocked.md").exists())
        } finally {
            releaseScan.countDown()
        }
        listOf(syncJob, saveJob, readJob).joinAll()

        val leave = events.indexOf("sync:leave")
        assertTrue(leave >= 0)
        assertTrue(leave < events.indexOf("save:done"))
        assertTrue(leave < events.indexOf("read:done"))
        assertEquals("body", secondLibrary.get("blocked.md")?.body)
    }
}
