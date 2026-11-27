package com.zhaoolee.notes.library

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LegacyNoteRecord(
    val id: String,
    val content: String,
    val category: String = "",
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val images: String = "[]",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val footerText: String = ""
)

data class MigrationFailure(val id: String, val message: String)

data class MigrationReport(
    val migrated: Int,
    val skipped: Int,
    val failed: Int,
    val backupPath: String?,
    val databaseUnchanged: Boolean,
    val warnings: List<String> = emptyList(),
    val errors: List<MigrationFailure> = emptyList(),
    val completed: Boolean = false
)

private data class MigrationMarker(
    var v: Int = 1,
    var completed: Boolean = false,
    var completedAt: Long = 0,
    var databaseSize: Long = 0,
    var databaseSha256: String = "",
    var migrated: MutableMap<String, String> = linkedMapOf()
)

class LegacyNotesMigrator(
    private val library: NoteLibrary,
    private val markerFile: File,
    private val backupDir: File,
    private val clock: Clock = Clock.systemUTC()
) {
    private val mutex = Mutex()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun migrate(
        records: List<LegacyNoteRecord>,
        databaseFile: File?
    ): MigrationReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            val beforeHash = databaseFile?.takeIf { it.isFile }?.let(AtomicFiles::sha256Unlimited) ?: ""
            val beforeSize = databaseFile?.takeIf { it.isFile }?.length() ?: 0
            val marker = loadMarker()
            val backup = backupDatabase(databaseFile)
            val databaseChanged = marker.completed &&
                (beforeHash != marker.databaseSha256 || beforeSize != marker.databaseSize)
            if (marker.completed && !databaseChanged && records.all { it.id in marker.migrated }) {
                return@withLock MigrationReport(
                    migrated = 0,
                    skipped = records.size,
                    failed = 0,
                    backupPath = backup?.path,
                    databaseUnchanged = true,
                    completed = true
                )
            }

            var migrated = 0
            var skipped = 0
            var failed = 0
            val warnings = ArrayList<String>()
            val errors = ArrayList<MigrationFailure>()
            val unique = records.distinctBy { it.id }
            val claimed = marker.migrated.values.toMutableSet()
            for (record in unique) {
                if (record.id in marker.migrated) {
                    skipped++
                    continue
                }
                try {
                    val createdAt = (record.createdAt.takeIf { it > 0 } ?: record.updatedAt)
                        .takeIf { it > 0 } ?: clock.millis()
                    val body = record.content
                    val title = legacyTitle(body, createdAt)
                    val folder = legacyFolder(record.category)
                    val existing = library.list(folder = folder).firstOrNull {
                        it.editable && it.title == title && it.body == body &&
                            it.relativePath !in claimed
                    }
                    val note = if (existing != null) {
                        existing
                    } else {
                        library.create(title, folder, body, createdAt).also {
                            claimed.add(it.relativePath)
                        }
                    }
                    if (record.isPinned) library.setPinned(note.relativePath, true)
                    if (record.isFavorite) library.setFavorite(note.relativePath, true)
                    marker.migrated[record.id] = note.relativePath
                    claimed.add(note.relativePath)
                    if (existing != null) skipped++ else migrated++
                    if (record.images.isNotBlank() && record.images != "[]") {
                        warnings.add("便签 ${record.id} 的图片字段未导入；原 Room 数据仍保留")
                    }
                    saveMarker(marker)
                } catch (e: Exception) {
                    failed++
                    errors.add(MigrationFailure(record.id, e.message ?: e.javaClass.simpleName))
                }
            }

            val afterHash = databaseFile?.takeIf { it.isFile }?.let(AtomicFiles::sha256Unlimited) ?: ""
            val afterSize = databaseFile?.takeIf { it.isFile }?.length() ?: 0
            val unchanged = beforeHash == afterHash && beforeSize == afterSize
            marker.completed = errors.isEmpty()
            marker.completedAt = if (marker.completed) clock.millis() else 0
            marker.databaseSize = afterSize
            marker.databaseSha256 = afterHash
            saveMarker(marker)
            MigrationReport(
                migrated = migrated,
                skipped = skipped,
                failed = failed,
                backupPath = backup?.path,
                databaseUnchanged = unchanged,
                warnings = warnings,
                errors = errors,
                completed = marker.completed
            )
        }
    }

    private fun legacyTitle(content: String, createdAt: Long): String {
        val first = content.removePrefix("\uFEFF").lineSequence().firstOrNull().orEmpty().trim()
        val withoutHeading = first.replace(Regex("^#{1,6}\\s+"), "").trim()
        val candidate = if (withoutHeading.isEmpty()) {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm")
                .withZone(ZoneId.systemDefault())
            "便签 ${formatter.format(Instant.ofEpochMilli(createdAt))}"
        } else {
            PathRules.truncateCodePoints(withoutHeading, PathRules.MAX_TITLE_PARAM)
        }
        return PathRules.sanitizeTitle(candidate)
    }

    private fun legacyFolder(category: String): String {
        if (category.isBlank()) return ""
        return PathRules.sanitizeFolder(category.replace('/', ' '))
    }

    private fun backupDatabase(databaseFile: File?): File? {
        if (databaseFile == null || !databaseFile.isFile) return null
        backupDir.mkdirs()
        val backup = File(backupDir, "smartisan_notes.db.bak")
        if (backup.exists()) return backup
        val created = ArrayList<File>()
        try {
            AtomicFiles.copy(databaseFile, backup)
            created.add(backup)
            for (suffix in listOf("-wal", "-shm")) {
                val source = File(databaseFile.parentFile, databaseFile.name + suffix)
                if (!source.isFile) continue
                val target = File(backup.parentFile, backup.name + suffix)
                AtomicFiles.copy(source, target)
                created.add(target)
            }
        } catch (e: Exception) {
            created.forEach { it.delete() }
            throw e
        }
        return backup
    }

    private fun loadMarker(): MigrationMarker {
        if (!markerFile.isFile) return MigrationMarker()
        return try {
            val json = JsonParser.parseString(AtomicFiles.readText(markerFile))
            require(json.isJsonObject) { "migration marker root" }
            val objectValue = json.asJsonObject
            val migrated = objectValue.get("migrated")
            if (migrated == null || migrated.isJsonNull) objectValue.add("migrated", JsonObject())
            gson.fromJson(objectValue, MigrationMarker::class.java)
        } catch (_: Exception) {
            val corrupt = File(markerFile.parentFile, "${markerFile.name}.corrupt-${clock.millis()}")
            markerFile.renameTo(corrupt)
            MigrationMarker()
        }
    }

    private fun saveMarker(marker: MigrationMarker) {
        marker.v = 1
        markerFile.parentFile?.mkdirs()
        AtomicFiles.writeText(markerFile, gson.toJson(marker))
    }
}
