package com.zhaoolee.notes.library

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Clock

internal data class MetadataEntry(
    var pinned: Boolean = false,
    var favorite: Boolean = false,
    var createdAt: Long = 0,
    var lastSeenAt: Long = 0
)

internal data class MetadataDocument(
    var v: Int = 1,
    var notes: MutableMap<String, MetadataEntry> = linkedMapOf()
)

internal class LibraryMetadata(private val file: File, private val clock: Clock) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var loaded = false
    private var dirty = false
    private var doc = MetadataDocument()

    fun entry(relativePath: String, fallbackCreatedAt: Long): MetadataEntry {
        load()
        val now = clock.millis()
        val entry = doc.notes.getOrPut(relativePath) {
            dirty = true
            MetadataEntry(createdAt = fallbackCreatedAt.coerceAtLeast(0), lastSeenAt = now)
        }
        if (now - entry.lastSeenAt > DAY_MS) {
            entry.lastSeenAt = now
            dirty = true
        }
        if (entry.createdAt <= 0) {
            entry.createdAt = fallbackCreatedAt.coerceAtLeast(0)
            dirty = true
        }
        return entry
    }

    fun rename(old: String, new: String) {
        load()
        val entry = doc.notes.remove(old) ?: return
        doc.notes[new] = entry
        dirty = true
    }

    fun remove(relativePath: String) {
        load()
        if (doc.notes.remove(relativePath) != null) dirty = true
    }

    fun prune(present: Set<String>) {
        load()
        val cutoff = clock.millis() - MISSING_RETENTION_MS
        val removed = doc.notes.entries.removeIf { (path, entry) ->
            path !in present && entry.lastSeenAt in 1..cutoff
        }
        if (removed) dirty = true
    }

    fun saveIfDirty() {
        if (!dirty) return
        file.parentFile?.mkdirs()
        AtomicFiles.writeText(file, gson.toJson(doc))
        dirty = false
    }

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        doc = try {
            val text = AtomicFiles.readText(file)
            val json = JsonParser.parseString(text)
            require(json.isJsonObject) { "metadata root" }
            val objectValue = json.asJsonObject
            val notes = objectValue.get("notes")
            if (notes == null || notes.isJsonNull) objectValue.add("notes", JsonObject())
            gson.fromJson(objectValue, MetadataDocument::class.java)
        } catch (_: Exception) {
            val corrupt = File(file.parentFile, "${file.name}.corrupt-${clock.millis()}")
            file.renameTo(corrupt)
            dirty = true
            MetadataDocument()
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MISSING_RETENTION_MS = 30L * DAY_MS
    }
}
