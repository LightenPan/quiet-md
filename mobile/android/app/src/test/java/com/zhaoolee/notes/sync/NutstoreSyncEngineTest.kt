package com.zhaoolee.notes.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class NutstoreSyncEngineTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var root: File
    private lateinit var stateDir: File
    private lateinit var fake: FakeWebDav
    private val clock = Clock.fixed(Instant.parse("2026-11-25T12:30:00Z"), ZoneId.of("UTC"))
    private val config = WebDavConfig.create(
        server = "https://dav.example/dav/",
        username = "me@example.com",
        remoteRoot = "Quiet.md"
    )

    @Before
    fun setUp() {
        root = temporary.newFolder("library")
        stateDir = File(temporary.newFolder("state"), "nutstore")
        fake = FakeWebDav()
    }

    private fun engine(): NutstoreSyncEngine = NutstoreSyncEngine(
        SyncOptions(root, stateDir, config, "Android", clock)
    )

    private fun write(rel: String, text: String) {
        val file = File(root, rel)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    private fun read(rel: String): String = File(root, rel).readText(Charsets.UTF_8)

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun localConflictFiles(): List<File> =
        root.walkTopDown().filter { it.isFile && it.name.contains("冲突") }.toList()

    @Test
    fun initial_sync_asks_then_merge_preserves_both_libraries() = runBlocking {
        write("local.md", "local")
        fake.putFile("remote.md", "remote".toByteArray())
        val engine = engine()

        val choice = engine.sync(fake, InitialPolicy.ASK) as SyncOutcome.NeedsInitialChoice
        assertEquals(1, choice.choice.localFiles)
        assertEquals(1, choice.choice.remoteFiles)
        assertEquals("local", read("local.md"))
        assertEquals("remote", fake.files.getValue("remote.md").bytes.toString(Charsets.UTF_8))

        val merged = engine.sync(fake, InitialPolicy.MERGE_BOTH) as SyncOutcome.Synced
        assertEquals("local", read("local.md"))
        assertEquals("remote", read("remote.md"))
        assertEquals("local", fake.files.getValue("local.md").bytes.toString(Charsets.UTF_8))
        assertTrue(merged.report.uploaded >= 1)
        assertTrue(merged.report.downloaded >= 1)
    }

    @Test
    fun initial_merge_keeps_same_name_versions_apart() = runBlocking {
        write("a.md", "local")
        fake.putFile("a.md", "remote".toByteArray())
        val engine = engine()
        engine.sync(fake, InitialPolicy.ASK)

        val report = (engine.sync(fake, InitialPolicy.MERGE_BOTH) as SyncOutcome.Synced).report
        assertEquals("local", read("a.md"))
        assertEquals("local", fake.files.getValue("a.md").bytes.toString(Charsets.UTF_8))
        val conflicts = localConflictFiles()
        assertEquals(1, conflicts.size)
        assertEquals("remote", conflicts.single().readText(Charsets.UTF_8))
        assertTrue(conflicts.single().name.contains("2026-11-25 12-30"))
        assertTrue(report.conflicts >= 1)
        assertTrue(fake.files.keys.any { it.contains("冲突") })
    }

    @Test
    fun local_change_is_uploaded() = runBlocking {
        write("a.md", "v1")
        val engine = engine()
        engine.sync(fake, InitialPolicy.ASK)
        write("a.md", "v2")

        val report = (engine.sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report
        assertEquals("v2", fake.files.getValue("a.md").bytes.toString(Charsets.UTF_8))
        assertEquals(1, report.uploaded)
    }

    @Test
    fun remote_change_is_downloaded() = runBlocking {
        write("a.md", "v1")
        val engine = engine()
        engine.sync(fake, InitialPolicy.ASK)
        fake.replaceFile("a.md", "v2".toByteArray())

        val report = (engine.sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report
        assertEquals("v2", read("a.md"))
        assertEquals(1, report.downloaded)
    }

    @Test
    fun concurrent_changes_keep_local_main_and_remote_conflict() = runBlocking {
        write("a.md", "base")
        val engine = engine()
        engine.sync(fake, InitialPolicy.ASK)
        write("a.md", "local")
        fake.replaceFile("a.md", "remote".toByteArray())

        val report = (engine.sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report
        assertEquals("local", read("a.md"))
        assertEquals("local", fake.files.getValue("a.md").bytes.toString(Charsets.UTF_8))
        val conflict = localConflictFiles().single()
        assertEquals("remote", conflict.readText(Charsets.UTF_8))
        assertEquals("remote", fake.files.getValue("a（冲突 · Android · 2026-11-25 12-30）.md").bytes.toString(Charsets.UTF_8))
        assertTrue(report.conflicts >= 1)
    }

    @Test
    fun remote_delete_quarantines_an_unchanged_local_file() = runBlocking {
        write("a.md", "a")
        write("b.md", "b")
        val engine = engine()
        engine.sync(fake, InitialPolicy.ASK)
        fake.deleteFile("a.md")

        val report = (engine.sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report
        assertFalse(File(root, "a.md").exists())
        assertTrue(File(stateDir, "removed/a.md").isFile)
        assertEquals(1, report.localMovedToRecovery)
    }

    @Test
    fun local_delete_removes_remote_file() = runBlocking {
        write("a.md", "a")
        val engine = engine()
        engine.sync(fake, InitialPolicy.ASK)
        File(root, "a.md").delete()

        val report = (engine.sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report
        assertFalse(fake.files.containsKey("a.md"))
        assertEquals(1, report.remoteDeleted)
    }

    @Test
    fun unexpectedly_empty_remote_pauses_sync() = runBlocking {
        write("a.md", "a")
        val engine = engine()
        engine.sync(fake, InitialPolicy.ASK)
        fake.clearFiles()

        try {
            engine.sync(fake, InitialPolicy.ASK)
            fail("expected SyncException")
        } catch (e: SyncException) {
            assertTrue(e.message.orEmpty().contains("突然为空"))
        }
        assertEquals("a", read("a.md"))
    }

    @Test
    fun local_rename_uses_remote_move() = runBlocking {
        write("a.md", "a")
        val engine = engine()
        engine.sync(fake, InitialPolicy.ASK)
        assertTrue(File(root, "a.md").renameTo(File(root, "b.md")))

        val report = (engine.sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report
        assertEquals(listOf("a.md" to "b.md"), fake.moves)
        assertFalse(fake.files.containsKey("a.md"))
        assertTrue(fake.files.containsKey("b.md"))
        assertEquals(1, report.moved)
    }

    @Test
    fun remote_rename_moves_local_file_without_a_conflict() = runBlocking {
        write("a.md", "a")
        val engine = engine()
        engine.sync(fake, InitialPolicy.ASK)
        fake.move("a.md", "b.md", false)

        val report = (engine.sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report
        assertFalse(File(root, "a.md").exists())
        assertEquals("a", read("b.md"))
        assertEquals(1, report.moved)
        assertTrue(localConflictFiles().isEmpty())
    }

    @Test
    fun nested_files_get_remote_directories() = runBlocking {
        write("Work/周报.md", "weekly")
        engine().sync(fake, InitialPolicy.ASK)
        assertTrue(fake.dirs.contains("Work"))
        assertNotNull(fake.files["Work/周报.md"])
    }

    @Test
    fun protected_local_file_is_not_mistaken_for_a_deletion() = runBlocking {
        write("a.md", "a")
        write("b.md", "b")
        engine().sync(fake, InitialPolicy.ASK)
        File(root, "a.md").delete()
        val delegate = JavaSyncFileSystem(root, File(stateDir, "removed"))
        val files = object : SyncFileSystem by delegate {
            override fun scan(): LocalScan = delegate.scan().also { it.protectedPaths.add("a.md") }
        }
        val second = NutstoreSyncEngine(
            SyncOptions(root, stateDir, config, "Android", clock),
            fileSystem = files
        )

        val report = (second.sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report
        assertTrue(fake.files.containsKey("a.md"))
        assertTrue(report.warnings.any { it.contains("未从坚果云删除") })
    }

    @Test
    fun initial_sync_never_overwrites_a_protected_local_file() = runBlocking {
        write("a.md", "local")
        fake.putFile("a.md", "remote".toByteArray())
        val delegate = JavaSyncFileSystem(root, File(stateDir, "removed"))
        val protectedFs = object : SyncFileSystem by delegate {
            override fun scan(): LocalScan = delegate.scan().also {
                it.files.remove("a.md")
                it.protectedPaths.add("a.md")
            }
        }
        val engine = NutstoreSyncEngine(
            SyncOptions(root, stateDir, config, "Android", clock),
            fileSystem = protectedFs
        )

        val report = (engine.sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report

        assertEquals("local", read("a.md"))
        assertEquals("remote", fake.files.getValue("a.md").bytes.toString(Charsets.UTF_8))
        assertTrue(report.warnings.any { it.contains("未下载覆盖") })
    }

    @Test
    fun case_only_collision_is_rejected_before_any_write() = runBlocking {
        write("a.md", "local")
        fake.putFile("A.md", "remote".toByteArray())
        val engine = engine()

        try {
            engine.sync(fake, InitialPolicy.ASK)
            fail("expected SyncException")
        } catch (e: SyncException) {
            assertTrue(e.message.orEmpty().contains("仅大小写不同"))
        }

        assertEquals("local", read("a.md"))
        assertFalse(root.listFiles().orEmpty().any { it.name == "A.md" })
        assertEquals("remote", fake.files.getValue("A.md").bytes.toString(Charsets.UTF_8))
        assertFalse(fake.files.containsKey("a.md"))
    }

    @Test
    fun file_directory_conflict_is_rejected_before_download() = runBlocking {
        val localDirectory = File(root, "a.md").apply { assertTrue(mkdirs()) }
        fake.putFile("a.md", "remote".toByteArray())

        try {
            engine().sync(fake, InitialPolicy.ASK)
            fail("expected SyncException")
        } catch (e: SyncException) {
            assertTrue(e.message.orEmpty().contains("类型不同"))
        }

        assertTrue(localDirectory.isDirectory)
        assertEquals("remote", fake.files.getValue("a.md").bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun remote_delete_keeps_and_reuploads_a_locally_changed_file() = runBlocking {
        write("a.md", "base")
        write("keep.md", "keep")
        val engine = engine()
        engine.sync(fake, InitialPolicy.ASK)
        write("a.md", "local change")
        fake.deleteFile("a.md")

        val report = (engine.sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report

        assertEquals("local change", read("a.md"))
        assertEquals("local change", fake.files.getValue("a.md").bytes.toString(Charsets.UTF_8))
        assertTrue(report.warnings.any { it.contains("本机有修改") })
    }

    @Test
    fun local_delete_restores_a_remotely_changed_file() = runBlocking {
        write("a.md", "base")
        val engine = engine()
        engine.sync(fake, InitialPolicy.ASK)
        File(root, "a.md").delete()
        fake.replaceFile("a.md", "remote change".toByteArray())

        val report = (engine.sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report

        assertEquals("remote change", read("a.md"))
        assertEquals(1, report.downloaded)
    }

    @Test
    fun sync_reports_phases_and_transfer_progress() = runBlocking {
        write("a.md", "a")
        write("Work/b.md", "b")
        val seen = mutableListOf<SyncProgress>()
        val engine = NutstoreSyncEngine(
            SyncOptions(root, stateDir, config, "Android", clock),
            onProgress = { seen.add(it) }
        )

        val report = (engine.sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report

        assertTrue(seen.any { it.phase == SyncPhase.CONNECTING })
        assertTrue(seen.any { it.phase == SyncPhase.SCANNING_REMOTE })
        assertTrue(seen.any { it.phase == SyncPhase.SCANNING_LOCAL && it.remoteFiles == 0 })
        assertTrue(seen.any { it.phase == SyncPhase.UPLOADING && it.total == 2 && it.detail == "Work/b.md" })
        assertEquals(SyncPhase.DONE, seen.last().phase)
        assertEquals(report.uploaded, seen.last().uploaded)
        assertEquals(2, seen.last().uploaded)
    }

    @Test
    fun initial_merge_resumes_and_does_not_refetch_saved_downloads() = runBlocking {
        write("local.md", "local")
        repeat(6) { index -> fake.putFile("remote-$index.md", "remote $index".toByteArray()) }
        fake.failGetAfter = 2

        try {
            engine().sync(fake, InitialPolicy.MERGE_BOTH)
            fail("expected rate limit")
        } catch (_: WebDavException) {
        }

        val saved = SyncStateStore(stateDir).load().state
        assertFalse(saved.scanComplete)
        assertEquals(InitialPolicy.MERGE_BOTH.name, saved.initialPolicy)
        assertEquals(2, saved.files.keys.count { it.startsWith("remote-") })
        assertEquals(3, fake.getCalls)

        fake.failGetAfter = null
        val report = (engine().sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report

        assertEquals(7, fake.getCalls)
        assertEquals(4, report.downloaded)
        assertTrue((0..5).all { File(root, "remote-$it.md").isFile })
        assertEquals("local", fake.files.getValue("local.md").bytes.toString(Charsets.UTF_8))
        assertTrue(SyncStateStore(stateDir).load().state.scanComplete)
    }

    @Test
    fun content_hash_etag_is_compared_without_downloading() = runBlocking {
        val text = "same content"
        write("a.md", text)
        fake.putFile("a.md", text.toByteArray(), etag = "\"${md5(text.toByteArray())}\"")

        val report = (engine().sync(fake, InitialPolicy.MERGE_BOTH) as SyncOutcome.Synced).report

        assertEquals(0, fake.getCalls)
        assertEquals(1, report.fingerprintSkipped)
        assertEquals(0, report.uploaded)
        assertEquals(text, read("a.md"))
        assertTrue(SyncStateStore(stateDir).load().state.files.containsKey("a.md"))
    }

    @Test
    fun sha256_etag_is_compared_without_reading_the_file_again() = runBlocking {
        val text = "same sha content"
        write("a.md", text)
        fake.putFile("a.md", text.toByteArray(), etag = "\"${sha256(text.toByteArray())}\"")

        val report = (engine().sync(fake, InitialPolicy.MERGE_BOTH) as SyncOutcome.Synced).report

        assertEquals(0, fake.getCalls)
        assertEquals(1, report.fingerprintSkipped)
        assertEquals(0, report.uploaded)
    }

    @Test
    fun changed_content_hash_etag_skips_download_when_local_already_matches() = runBlocking {
        val text = "same incremental content"
        write("a.md", text)
        fake.putFile("a.md", text.toByteArray(), etag = "\"old\"")
        val engine = engine()
        engine.sync(fake, InitialPolicy.MERGE_BOTH)
        val reads = fake.getCalls

        fake.putFile("a.md", text.toByteArray(), etag = "\"${md5(text.toByteArray())}\"")
        val report = (engine.sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report

        assertEquals(reads, fake.getCalls)
        assertEquals(1, report.fingerprintSkipped)
        assertEquals(0, report.downloaded)
    }

    @Test
    fun unchanged_incremental_sync_transfers_no_file_contents() = runBlocking {
        write("a.md", "stable")
        val engine = engine()
        engine.sync(fake, InitialPolicy.ASK)

        val report = (engine.sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report

        assertEquals(0, fake.getCalls)
        assertEquals(0, report.uploaded)
        assertEquals(0, report.downloaded)
    }

    @Test
    fun identical_fingerprint_overlap_does_not_ask_for_merge() = runBlocking {
        val text = "same initial content"
        write("a.md", text)
        fake.putFile("a.md", text.toByteArray(), etag = "\"${md5(text.toByteArray())}\"")

        val outcome = engine().sync(fake, InitialPolicy.ASK)

        assertTrue(outcome is SyncOutcome.Synced)
        assertEquals(0, fake.getCalls)
        assertEquals(1, (outcome as SyncOutcome.Synced).report.fingerprintSkipped)
    }

    @Test
    fun initial_choice_counts_only_real_content_differences() = runBlocking {
        write("a.md", "local")
        fake.putFile("a.md", "different".toByteArray(), etag = "\"e1\"")

        val choice = engine().sync(fake, InitialPolicy.ASK) as SyncOutcome.NeedsInitialChoice

        assertEquals(1, choice.choice.conflicts)
        assertEquals(0, choice.choice.unverified)
        assertEquals(0, fake.getCalls)
    }

    @Test
    fun hash_mismatch_is_verified_during_merge_instead_of_blocking() = runBlocking {
        write("a.md", "local")
        fake.putFile("a.md", "remot".toByteArray(), etag = "\"${md5("remot".toByteArray())}\"")

        val report = (engine().sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report

        assertEquals(1, fake.getCalls)
        assertEquals(1, report.conflicts)
    }

    @Test
    fun non_hash_etag_is_verified_automatically() = runBlocking {
        write("a.md", "same")
        fake.putFile("a.md", "same".toByteArray(), etag = "\"e1\"")

        val report = (engine().sync(fake, InitialPolicy.ASK) as SyncOutcome.Synced).report

        assertEquals(1, fake.getCalls)
        assertEquals(1, report.verified)
        assertEquals(0, report.conflicts)
    }
}
