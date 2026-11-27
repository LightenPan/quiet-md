package com.zhaoolee.notes.data

import kotlinx.coroutines.flow.Flow

class NoteRepository(private val noteDao: NoteDao) {
    val allNotes: Flow<List<Note>> = noteDao.getAllNotes()
    val allCategories: Flow<List<String>> = noteDao.getAllCategories()
    val favoriteNotes: Flow<List<Note>> = noteDao.getFavoriteNotes()
    fun getNotesByCategory(category: String): Flow<List<Note>> = noteDao.getNotesByCategory(category)
    fun searchNotes(query: String): Flow<List<Note>> = noteDao.searchNotes(query)
    suspend fun getNoteById(noteId: String): Note? = noteDao.getNoteById(noteId)
    suspend fun insertNote(note: Note) = noteDao.insertNote(note)
    suspend fun updateNote(note: Note) = noteDao.updateNote(note.copy(updatedAt = System.currentTimeMillis()))
    suspend fun deleteNote(note: Note) = noteDao.deleteNote(note)
    suspend fun toggleFavorite(noteId: String) {
        noteDao.getNoteById(noteId)?.let { noteDao.updateNote(it.copy(isFavorite = !it.isFavorite, updatedAt = System.currentTimeMillis())) }
    }
    suspend fun togglePin(noteId: String) {
        noteDao.getNoteById(noteId)?.let { noteDao.updateNote(it.copy(isPinned = !it.isPinned, updatedAt = System.currentTimeMillis())) }
    }
}
