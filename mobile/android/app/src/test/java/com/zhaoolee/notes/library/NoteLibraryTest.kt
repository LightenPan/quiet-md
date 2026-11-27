package com.zhaoolee.notes.library

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun library(): NoteLibrary = NoteLibrary(
        File(tmp.root, "Quiet.md"),
        File(tmp.root, "library-meta.json")
    )

    @Test
    fun createSanitizesTitleAndRoundTripsBody() = runBlocking {
        val library = library()
        val note = library.create("  hello::world  ", "Work", "第一行\n第二行")
        assertEquals("hello world", note.title)
        assertEquals("Work/hello world.md", note.relativePath)
        assertEquals("第一行\n第二行", note.body)
        assertTrue(note.editable)
        assertEquals(note, library.get(note.relativePath))

        val reserved = library.create("CON", "", "x")
        assertEquals("CON_", reserved.title)
        val hidden = library.create(".hidden", "", "x")
        assertEquals("hidden", hidden.title)
    }

    @Test
    fun duplicateTitlesAndFoldersStayUnique() = runBlocking {
        val library = library()
        val first = library.create("Same", "Work", "one")
        val second = library.create("Same", "Work", "two")
        val root = library.create("Same", "", "three")
        assertEquals("Work/Same.md", first.relativePath)
        assertEquals("Work/Same 2.md", second.relativePath)
        assertEquals("Same.md", root.relativePath)
        assertEquals("two", library.get(second.relativePath)?.body)
    }

    @Test
    fun saveDetectsExternalChangesAndCanRename() = runBlocking {
        val library = library()
        val original = library.create("Old", "Notes", "one")
        File(library.root, original.relativePath).writeText("changed elsewhere")
        try {
            library.save(original.copy(body = "ours"))
            fail("expected NoteConflictException")
        } catch (_: NoteConflictException) {
        }

        val latest = library.get(original.relativePath)!!
        val renamed = library.save(latest.copy(body = "ours"), "New title")
        assertEquals("New title", renamed.title)
        assertEquals("Notes/New title.md", renamed.relativePath)
        assertEquals("ours", renamed.body)
        assertFalse(File(library.root, original.relativePath).exists())
    }

    @Test
    fun listSearchesFiltersAndOrdersPinnedNumbersLatinAndChinese() = runBlocking {
        val library = library()
        val notes = listOf(
            library.create("香蕉", "Fruit", "yellow"),
            library.create("2 条", "Work", "two"),
            library.create("10 条", "Work", "ten"),
            library.create("Apple", "Fruit", "red"),
            library.create("苹果", "Fruit", "red"),
            library.create("Banana", "Fruit", "yellow"),
            library.create("Pinned", "Other", "pin")
        )
        val fixed = 1_700_000_000_000L
        notes.forEach { File(library.root, it.relativePath).setLastModified(fixed) }
        library.setPinned("Other/Pinned.md", true)

        val all = library.list()
        assertEquals("Pinned", all.first().title)
        val titles = all.map { it.title }
        assertTrue(titles.indexOf("2 条") < titles.indexOf("Apple"))
        assertTrue(titles.indexOf("10 条") < titles.indexOf("Apple"))
        assertTrue(titles.indexOf("Apple") < titles.indexOf("苹果"))
        assertTrue(titles.indexOf("苹果") < titles.indexOf("香蕉"))

        assertEquals(listOf("Apple", "苹果"), library.list("red", "Fruit").map { it.title }.sorted())
        assertEquals(listOf("2 条", "10 条"), library.list(folder = "Work").map { it.title })
        assertEquals(listOf("Apple"), library.list("App", "Fruit").map { it.title })
    }

    @Test
    fun moveAndDeleteKeepContentAndUseTrash() = runBlocking {
        val library = library()
        val note = library.create("Plan", "Inbox", "keep me")
        val moved = library.move(note.relativePath, "Archive/2026")
        assertEquals("Archive/2026/Plan.md", moved.relativePath)
        assertEquals("keep me", moved.body)

        library.delete(moved.relativePath)
        assertEquals(null, library.get(moved.relativePath))
        val trash = File(library.root, ".trash")
        val trashed = trash.listFiles()!!.single()
        assertEquals("keep me", trashed.readText())
        assertEquals(listOf("Archive", "Archive/2026", "Inbox"), library.folders())
    }

    @Test
    fun xmlIsListedButReadOnlyAndTraversalIsRejected() = runBlocking {
        val library = library()
        val xml = File(library.root, "Rich.xml")
        xml.writeText("<doc/>")
        val note = library.get("Rich.xml")!!
        assertFalse(note.editable)
        try {
            library.save(note.copy(body = "no"))
            fail("xml must be read only")
        } catch (_: IllegalArgumentException) {
        }
        try {
            library.get("../outside.md")
            fail("path traversal must fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun corruptMetadataIsQuarantinedAndRebuilt() = runBlocking {
        val library = library()
        val note = library.create("Stable", "", "body")
        library.metadataFile.writeText("{not json")
        val fresh = NoteLibrary(File(tmp.root, "Quiet.md"), library.metadataFile)
        assertEquals("body", fresh.get(note.relativePath)?.body)
        assertTrue(tmp.root.listFiles()!!.any {
            it.name.startsWith("library-meta.json.corrupt-")
        })
    }

    @Test
    fun metadataTracksFlagsAndRenamesWithoutTouchingBody() = runBlocking {
        val library = library()
        val note = library.create("Meta", "", "body")
        library.setFavorite(note.relativePath, true)
        library.setPinned(note.relativePath, true)
        val marked = library.get(note.relativePath)!!
        assertTrue(marked.favorite)
        assertTrue(marked.pinned)
        val renamed = library.save(marked.copy(body = "body"), "Meta 2")
        assertNotEquals(note.relativePath, renamed.relativePath)
        assertTrue(library.get(renamed.relativePath)!!.favorite)
        assertTrue(library.get(renamed.relativePath)!!.pinned)
    }

    @Test
    fun longUnicodeTitlesStayInsideTheFilesystemNameLimit() = runBlocking {
        val library = library()
        val note = library.create("汉".repeat(120), "", "body")
        val name = note.relativePath.substringAfterLast('/')
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
        assertEquals("body", library.get(note.relativePath)?.body)
    }

    @Test
    fun nfdAndNfcNamesCannotCreateVisuallyIdenticalDuplicates() = runBlocking {
        val library = library()
        File(library.root, "Cafe\u0301.md").writeText("external", Charsets.UTF_8)
        val note = library.create("Café", "", "ours")
        assertEquals("Café 2.md", note.relativePath)
        assertEquals(setOf("external", "ours"), library.list().map { it.body }.toSet())
    }

    @Test
    fun hiddenLibraryPathsAreNotAddressable() = runBlocking {
        val library = library()
        val hidden = File(library.root, ".trash/old.md")
        hidden.parentFile?.mkdirs()
        hidden.writeText("old")
        try {
            library.get(".trash/old.md")
            fail("hidden path must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }
}
