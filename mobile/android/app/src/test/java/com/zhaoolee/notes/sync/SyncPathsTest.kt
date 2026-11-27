package com.zhaoolee.notes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SyncPathsTest {
    @Test
    fun server_and_remote_root_are_normalized() {
        assertEquals("https://dav.example/dav/", SyncPaths.normalizeServer(" https://dav.example/dav "))
        assertEquals(
            "Quiet.md/Android",
            SyncPaths.cleanRemoteRoot(" Quiet.md\\Android/ ")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun remote_root_cannot_escape() {
        SyncPaths.cleanRemoteRoot("../Quiet.md")
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsafe_remote_file_names_are_rejected() {
        SyncPaths.requireRelative("Work/CON.txt")
    }

    @Test(expected = IllegalArgumentException::class)
    fun remote_file_names_over_255_utf8_bytes_are_rejected() {
        SyncPaths.requireRelative("汉".repeat(86) + ".md")
    }

    @Test
    fun nfc_and_percent_encoding_are_stable() {
        val decomposed = "Cafe\u0301/周报.md"
        assertEquals("Café/周报.md", SyncPaths.nfc(decomposed))
        assertEquals("Caf%C3%A9", SyncPaths.encodeSegment("Café"))
        assertEquals("Work", SyncPaths.parentRel("Work/周报.md"))
        assertEquals("周报", SyncPaths.stem("Work/周报.md"))
        assertEquals("md", SyncPaths.extension("Work/周报.md"))
    }

    @Test
    fun hidden_sync_paths_follow_the_whitelist() {
        assertTrue(SyncPaths.isSyncedFile("a.md", "a.md"))
        assertTrue(SyncPaths.isSyncedFile(".assets/picture.png", "picture.png"))
        assertFalse(SyncPaths.isSyncedFile(".assets/thumbs/picture.png", "picture.png"))
        assertFalse(SyncPaths.isSyncedFile(".trash/a.md", "a.md"))
        assertFalse(SyncPaths.isSyncedFile(".quiet", "index.json"))
    }

    @Test
    fun conflict_name_is_unique_and_safe() {
        val now = ZonedDateTime.of(2026, 11, 25, 12, 30, 0, 0, ZoneId.of("UTC"))
        val name = localConflictName(
            "Work/a:b.md",
            "Android/phone",
            setOf("Work/a b（冲突 · Android phone · 2026-11-25 12-30）.md"),
            { false },
            now
        )
        assertTrue(name.startsWith("Work/a b（冲突 · Android phone · 2026-11-25 12-30） (2).md"))
    }

    @Test
    fun conflict_name_stays_within_the_filesystem_byte_limit() {
        val now = ZonedDateTime.of(2026, 11, 25, 12, 30, 0, 0, ZoneId.of("UTC"))
        val name = localConflictName(
            "汉".repeat(120) + ".md",
            "手机".repeat(30),
            emptySet(),
            { false },
            now
        )
        assertTrue(name.substringAfterLast('/').toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(name.contains("冲突"))
    }
}
