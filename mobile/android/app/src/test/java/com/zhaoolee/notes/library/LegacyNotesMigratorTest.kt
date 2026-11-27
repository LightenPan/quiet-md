package com.zhaoolee.notes.library

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyNotesMigratorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun library() = NoteLibrary(
        File(tmp.root, "Quiet.md"),
        File(tmp.root, "library-meta.json")
    )

    private fun migrator(library: NoteLibrary) = LegacyNotesMigrator(
        library,
        File(tmp.root, "legacy-migration.json"),
        File(tmp.root, "legacy")
    )

    @Test
    fun migrationPreservesBodiesMapsFoldersAndIsIdempotent() = runBlocking {
        val library = library()
        val database = File(tmp.root, "smartisan_notes.db").apply { writeText("database bytes") }
        val original = database.readBytes()
        val records = listOf(
            LegacyNoteRecord(
                id = "one",
                content = "# 标题\n\n正文原样\r\n第二行",
                category = "Work",
                isPinned = true,
                isFavorite = true,
                createdAt = 1_700_000_000_000,
                updatedAt = 1_700_000_000_100
            ),
            LegacyNoteRecord(
                id = "two",
                content = "",
                category = "???",
                images = "[\"a.png\"]",
                createdAt = 1_700_000_100_000
            ),
            LegacyNoteRecord(
                id = "three",
                content = "标题\nsecond body",
                category = "Work",
                createdAt = 1_700_000_200_000
            ),
            LegacyNoteRecord(
                id = "four",
                content = "标题\nanother body",
                category = "Work",
                createdAt = 1_700_000_300_000
            )
        )

        val first = migrator(library).migrate(records, database)
        assertTrue(first.completed)
        assertEquals(4, first.migrated)
        assertEquals(0, first.failed)
        assertEquals(1, first.warnings.size)
        assertArrayEquals(original, database.readBytes())
        assertTrue(first.databaseUnchanged)
        assertEquals("database bytes", File(first.backupPath!!).readText())

        val migrated = library.list()
        assertEquals(4, migrated.size)
        val work = migrated.filter { it.folder == "Work" }.sortedBy { it.createdAt }
        assertEquals("标题", work[0].title)
        assertEquals("# 标题\n\n正文原样\r\n第二行", work[0].body)
        assertTrue(work[0].pinned)
        assertTrue(work[0].favorite)
        assertEquals("标题 2", work[1].title)
        assertEquals("标题 3", work[2].title)
        assertEquals("second body", work[1].body.substringAfter('\n'))
        assertEquals("another body", work[2].body.substringAfter('\n'))
        val untitled = migrated.first { it.folder == "默认" }
        assertTrue(untitled.title.startsWith("便签 "))

        val second = migrator(library).migrate(records, database)
        assertTrue(second.completed)
        assertEquals(0, second.migrated)
        assertEquals(4, second.skipped)
        assertEquals(4, library.list().size)
        assertArrayEquals(original, database.readBytes())
    }

    @Test
    fun migrationNeverOverwritesExistingUserFiles() = runBlocking {
        val library = library()
        val user = library.create("Same", "Work", "user content")
        val database = File(tmp.root, "smartisan_notes.db").apply { writeText("db") }
        val report = migrator(library).migrate(
            listOf(
                LegacyNoteRecord(
                    id = "legacy",
                    content = "Same\nlegacy content",
                    category = "Work",
                    createdAt = 1_700_000_000_000
                )
            ),
            database
        )
        assertTrue(report.completed)
        assertEquals("user content", library.get(user.relativePath)?.body)
        val all = library.list(folder = "Work")
        assertEquals(2, all.size)
        assertTrue(all.any { it.relativePath == "Work/Same 2.md" && it.body.contains("legacy") })
    }

    @Test
    fun interruptedMigrationReusesTheAlreadyWrittenNote() = runBlocking {
        val library = library()
        val existing = library.create(
            "标题",
            "",
            "标题\nsame body",
            1_700_000_000_000
        )
        val database = File(tmp.root, "smartisan_notes.db").apply { writeText("db") }
        val record = LegacyNoteRecord(
            id = "retry",
            content = "标题\nsame body",
            isPinned = true,
            createdAt = 1_700_000_000_000
        )

        val report = migrator(library).migrate(listOf(record), database)

        assertTrue(report.completed)
        assertEquals(0, report.migrated)
        assertEquals(1, report.skipped)
        assertEquals(1, library.list().size)
        assertTrue(library.get(existing.relativePath)!!.pinned)
    }

    @Test
    fun aChangedLegacyDatabaseStillMigratesOnlyNewIds() = runBlocking {
        val library = library()
        val database = File(tmp.root, "smartisan_notes.db").apply { writeText("v1") }
        val first = LegacyNoteRecord(
            id = "one",
            content = "first",
            createdAt = 1_700_000_000_000
        )
        val second = LegacyNoteRecord(
            id = "two",
            content = "second",
            createdAt = 1_700_000_100_000
        )
        assertTrue(migrator(library).migrate(listOf(first), database).completed)

        database.writeText("v2")
        val report = migrator(library).migrate(listOf(first, second), database)

        assertTrue(report.completed)
        assertEquals(1, report.migrated)
        assertEquals(1, report.skipped)
        assertEquals(setOf("first", "second"), library.list().map { it.body }.toSet())
    }

    @Test
    fun databaseBackupIncludesWalAndSharedMemorySidecars() = runBlocking {
        val library = library()
        val database = File(tmp.root, "smartisan_notes.db").apply { writeText("main") }
        File(tmp.root, "smartisan_notes.db-wal").writeText("wal")
        File(tmp.root, "smartisan_notes.db-shm").writeText("shm")

        val report = migrator(library).migrate(emptyList(), database)

        assertEquals("main", File(report.backupPath!!).readText())
        assertEquals("wal", File(report.backupPath!! + "-wal").readText())
        assertEquals("shm", File(report.backupPath!! + "-shm").readText())
    }

}
