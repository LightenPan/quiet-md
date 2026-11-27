package com.zhaoolee.notes.library

import android.content.Context
import com.zhaoolee.notes.data.Note
import com.zhaoolee.notes.data.NotesDatabase
import java.io.File
import kotlinx.coroutines.flow.first

object AndroidLegacyNotes {
    suspend fun migrate(context: Context, library: NoteLibrary = NoteLibrary.forContext(context)): MigrationReport {
        val databaseFile = context.getDatabasePath("smartisan_notes.db")
        if (!databaseFile.isFile) {
            return MigrationReport(
                migrated = 0,
                skipped = 0,
                failed = 0,
                backupPath = null,
                databaseUnchanged = true,
                completed = true
            )
        }
        val dao = NotesDatabase.getInstance(context).noteDao()
        val records = dao.getAllNotes().first().map { it.toLegacyRecord() }
        val migrator = LegacyNotesMigrator(
            library = library,
            markerFile = File(context.noBackupFilesDir, "legacy-migration.json"),
            backupDir = File(context.filesDir, "legacy")
        )
        return migrator.migrate(records, databaseFile)
    }

    private fun Note.toLegacyRecord() = LegacyNoteRecord(
        id = id,
        content = content,
        category = category,
        isPinned = isPinned,
        isFavorite = isFavorite,
        images = images,
        createdAt = createdAt,
        updatedAt = updatedAt,
        footerText = footerText
    )
}
