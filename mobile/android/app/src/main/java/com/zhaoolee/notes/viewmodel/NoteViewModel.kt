package com.zhaoolee.notes.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhaoolee.notes.library.AndroidLegacyNotes
import com.zhaoolee.notes.library.LibraryNote
import com.zhaoolee.notes.library.LibraryWarning
import com.zhaoolee.notes.library.MigrationReport
import com.zhaoolee.notes.library.NoteConflictException
import com.zhaoolee.notes.library.NoteLibrary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ViewMode { ALL, FAVORITES }

enum class SaveState { SAVED, DIRTY, SAVING, FAILED }

data class LibraryUiState(
    val notes: List<LibraryNote> = emptyList(),
    val folders: List<String> = emptyList(),
    val query: String = "",
    val selectedFolder: String? = null,
    val viewMode: ViewMode = ViewMode.ALL,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val warnings: List<LibraryWarning> = emptyList(),
    val migrationReport: MigrationReport? = null,
    val libraryPath: String = "",
    val totalNotes: Int = 0
)

data class EditorUiState(
    val relativePath: String = "",
    val note: LibraryNote? = null,
    val title: String = "",
    val body: String = "",
    val loading: Boolean = true,
    val dirty: Boolean = false,
    val saveState: SaveState = SaveState.SAVED,
    val error: String? = null,
    val conflict: Boolean = false,
    val readOnly: Boolean = false,
    val moving: Boolean = false,
    val moveError: String? = null
)

class NoteViewModel(application: Application) : AndroidViewModel(application) {
    private val library = NoteLibrary.forContext(application)
    private val _ui = MutableStateFlow(LibraryUiState(libraryPath = library.root.absolutePath))
    val ui: StateFlow<LibraryUiState> = _ui.asStateFlow()
    private val _editor = MutableStateFlow<EditorUiState?>(null)
    val editor: StateFlow<EditorUiState?> = _editor.asStateFlow()

    private val saveLock = Mutex()
    private val refreshLock = Mutex()
    private var saveJob: Job? = null
    private var refreshJob: Job? = null
    private var initialized = false

    init {
        viewModelScope.launch { initialize() }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { refreshLibrary() }
    }

    fun setQuery(query: String) {
        _ui.update { it.copy(query = query) }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(120)
            refreshLibrary()
        }
    }

    fun selectFolder(folder: String?) {
        _ui.update { it.copy(selectedFolder = folder) }
        refresh()
    }

    fun setViewMode(mode: ViewMode) {
        _ui.update {
            it.copy(
                viewMode = mode,
                selectedFolder = if (mode == ViewMode.FAVORITES) null else it.selectedFolder
            )
        }
        refresh()
    }

    fun createNote(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            if (!flushNow()) return@launch
            try {
                val note = library.create("无标题", _ui.value.selectedFolder.orEmpty())
                _editor.value = note.editorState()
                refreshLibrary()
                onCreated(note.relativePath)
            } catch (e: Exception) {
                _ui.update { it.copy(error = readable(e)) }
            }
        }
    }

    fun openNote(relativePath: String, onOpened: () -> Unit = {}) {
        viewModelScope.launch {
            if (!flushNow()) return@launch
            _editor.value = EditorUiState(relativePath = relativePath, loading = true)
            try {
                val note = library.get(relativePath)
                _editor.value = if (note == null) {
                    EditorUiState(
                        relativePath = relativePath,
                        loading = false,
                        error = "便签不存在或已被移走"
                    )
                } else {
                    note.editorState()
                }
            } catch (e: Exception) {
                _editor.value = EditorUiState(
                    relativePath = relativePath,
                    loading = false,
                    error = readable(e)
                )
            }
            onOpened()
        }
    }

    fun closeEditor(onClosed: () -> Unit) {
        viewModelScope.launch {
            if (!flushNow()) return@launch
            refreshLibrary()
            _editor.value = null
            onClosed()
        }
    }

    fun updateTitle(value: String) {
        val clean = value.replace('\n', ' ').replace('\r', ' ')
        _editor.update { state ->
            state?.copy(
                title = clean,
                dirty = true,
                saveState = SaveState.DIRTY,
                error = null,
                conflict = false
            )
        }
        scheduleSave()
    }

    fun updateBody(value: String) {
        _editor.update { state ->
            state?.copy(
                body = value,
                dirty = true,
                saveState = SaveState.DIRTY,
                error = null,
                conflict = false
            )
        }
        scheduleSave()
    }

    fun normalizeTitleOnBlur() {
        val state = _editor.value ?: return
        if (!state.readOnly && state.title.isBlank()) {
            val fallback = state.note?.title?.takeIf { it.isNotBlank() } ?: "无标题"
            _editor.update {
                it?.copy(title = fallback, dirty = true, saveState = SaveState.DIRTY)
            }
            scheduleSave()
        }
    }

    fun flush() {
        saveJob?.cancel()
        saveJob = null
        viewModelScope.launch { flushNow() }
    }

    fun retrySave() {
        _editor.update {
            it?.copy(saveState = SaveState.DIRTY, dirty = true, error = null, conflict = false)
        }
        scheduleSave(0)
    }

    fun reloadCurrent() {
        val path = _editor.value?.note?.relativePath ?: _editor.value?.relativePath ?: return
        saveJob?.cancel()
        saveJob = null
        viewModelScope.launch {
            saveLock.withLock {
                _editor.value = EditorUiState(relativePath = path, loading = true)
                try {
                    val note = library.get(path)
                    _editor.value = note?.editorState() ?: EditorUiState(
                        relativePath = path,
                        loading = false,
                        error = "便签不存在或已被移走"
                    )
                } catch (e: Exception) {
                    _editor.value = EditorUiState(
                        relativePath = path,
                        loading = false,
                        error = readable(e)
                    )
                }
            }
        }
    }

    fun toggleFavorite(note: LibraryNote) {
        viewModelScope.launch {
            try {
                library.setFavorite(note.relativePath, !note.favorite)
                updateOpenNote(note.relativePath)
                refreshLibrary()
            } catch (e: Exception) {
                _ui.update { it.copy(error = readable(e)) }
            }
        }
    }

    fun togglePinned(note: LibraryNote) {
        viewModelScope.launch {
            try {
                library.setPinned(note.relativePath, !note.pinned)
                updateOpenNote(note.relativePath)
                refreshLibrary()
            } catch (e: Exception) {
                _ui.update { it.copy(error = readable(e)) }
            }
        }
    }

    fun deleteCurrent(onDeleted: () -> Unit) {
        saveJob?.cancel()
        saveJob = null
        viewModelScope.launch {
            saveLock.withLock {
                val state = _editor.value ?: return@withLock
                val path = state.note?.relativePath ?: state.relativePath
                if (path.isBlank()) return@withLock
                try {
                    library.delete(path)
                    _editor.value = null
                    refreshLibrary()
                    onDeleted()
                } catch (e: Exception) {
                    _editor.update {
                        it?.copy(saveState = SaveState.FAILED, error = readable(e))
                    }
                }
            }
        }
    }

    fun clearMoveError() {
        _editor.update { it?.copy(moveError = null) }
    }

    fun moveCurrent(folder: String, onMoved: (String) -> Unit = {}) {
        val initial = _editor.value ?: return
        if (initial.moving) return
        if (initial.readOnly || initial.note?.editable != true) {
            _editor.update { it?.copy(moveError = "这篇文档不能在 Android 上移动") }
            return
        }
        saveJob?.cancel()
        saveJob = null
        _editor.update { it?.copy(moving = true, moveError = null) }
        viewModelScope.launch {
            val saveError = saveBeforeMove()
            if (saveError != null) {
                _editor.update {
                    it?.copy(
                        moving = false,
                        moveError = "便签没有移动：$saveError"
                    )
                }
                return@launch
            }
            try {
                val current = _editor.value
                    ?: throw IllegalStateException("当前便签已经关闭")
                val base = current.note
                    ?: throw IllegalStateException("当前便签尚未载入")
                val path = base.relativePath
                val disk = library.get(path)
                    ?: throw IllegalStateException("便签不存在或已被移走")
                if (disk.contentHash != base.contentHash) {
                    val message = "文件已在其他位置被修改，便签没有移动。请重新载入后再试。"
                    _editor.update {
                        it?.copy(
                            moving = false,
                            dirty = true,
                            saveState = SaveState.FAILED,
                            error = message,
                            conflict = true,
                            moveError = message
                        )
                    }
                    return@launch
                }
                val moved = library.move(path, folder)
                val latest = _editor.value ?: current
                _editor.update {
                    it?.copy(
                        relativePath = moved.relativePath,
                        note = moved,
                        title = latest.title,
                        body = latest.body,
                        dirty = false,
                        saveState = SaveState.SAVED,
                        error = null,
                        conflict = false,
                        moving = false,
                        moveError = null
                    )
                }
                refreshLibrary()
                onMoved(moved.relativePath)
            } catch (e: Exception) {
                _editor.update {
                    it?.copy(
                        moving = false,
                        moveError = "移动失败：${readable(e)}"
                    )
                }
            }
        }
    }

    private suspend fun saveBeforeMove(): String? {
        if (!flushNow()) return _editor.value?.error ?: "当前草稿没有保存"
        if (_editor.value?.dirty == true) {
            if (!flushNow()) return _editor.value?.error ?: "当前草稿仍在保存"
            if (_editor.value?.dirty == true) return "当前草稿仍在保存，请稍后重试"
        }
        return null
    }

    private suspend fun initialize() {
        if (initialized) return
        initialized = true
        _ui.update { it.copy(loading = true, error = null) }
        var migrationError: String? = null
        val report = try {
            AndroidLegacyNotes.migrate(getApplication(), library)
        } catch (e: Exception) {
            migrationError = "旧便签迁移失败：${readable(e)}"
            null
        }
        refreshLibrary()
        _ui.update {
            it.copy(
                migrationReport = report,
                error = migrationError ?: it.error
            )
        }
    }

    private suspend fun refreshLibrary() = refreshLock.withLock {
        val before = _ui.value
        _ui.update { it.copy(loading = it.notes.isEmpty(), refreshing = true, error = null) }
        try {
            val snapshot = library.snapshot(before.query, before.selectedFolder)
            val visible = if (before.viewMode == ViewMode.FAVORITES) {
                snapshot.notes.filter { it.favorite }
            } else {
                snapshot.notes
            }
            val folders = library.folders()
            _ui.update {
                it.copy(
                    notes = visible,
                    folders = folders,
                    totalNotes = snapshot.notes.size,
                    warnings = snapshot.warnings,
                    loading = false,
                    refreshing = false,
                    error = null
                )
            }
        } catch (e: Exception) {
            _ui.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    error = readable(e)
                )
            }
        }
    }

    private fun scheduleSave(delayMillis: Long = 500) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(delayMillis)
            saveJob = null
            flushNow()
        }
    }

    private suspend fun flushNow(): Boolean {
        val initial = _editor.value ?: return true
        if (!initial.dirty || initial.loading || initial.readOnly) return true
        return saveLock.withLock {
            val current = _editor.value ?: return@withLock true
            val base = current.note ?: return@withLock true
            if (!current.dirty || current.loading || current.readOnly) return@withLock true
            val title = current.title.trim().ifEmpty {
                base.title.takeIf { it.isNotBlank() } ?: "无标题"
            }
            _editor.update {
                it?.copy(
                    title = title,
                    saveState = SaveState.SAVING,
                    error = null,
                    conflict = false
                )
            }
            try {
                val saved = library.save(base.copy(title = title, body = current.body), title)
                val latest = _editor.value ?: return@withLock true
                val unchangedDraft = latest.title == title && latest.body == current.body
                _editor.value = if (unchangedDraft) {
                    latest.copy(
                        relativePath = saved.relativePath,
                        note = saved,
                        title = saved.title,
                        body = saved.body,
                        dirty = false,
                        saveState = SaveState.SAVED,
                        error = null,
                        conflict = false
                    )
                } else {
                    latest.copy(
                        relativePath = saved.relativePath,
                        note = saved,
                        dirty = true,
                        saveState = SaveState.DIRTY,
                        error = null,
                        conflict = false
                    )
                }
                if (!unchangedDraft) scheduleSave(120)
                true
            } catch (e: NoteConflictException) {
                _editor.update {
                    it?.copy(
                        saveState = SaveState.FAILED,
                        error = "文件已在其他位置被修改。当前草稿尚未保存，请重新载入或复制正文。",
                        conflict = true
                    )
                }
                false
            } catch (e: Exception) {
                _editor.update {
                    it?.copy(saveState = SaveState.FAILED, error = readable(e))
                }
                false
            }
        }
    }

    private suspend fun updateOpenNote(path: String) {
        val open = _editor.value ?: return
        if (open.note?.relativePath != path) return
        val updated = library.get(path) ?: return
        _editor.update {
            it?.copy(relativePath = updated.relativePath, note = updated)
        }
    }

    private fun LibraryNote.editorState() = EditorUiState(
        relativePath = relativePath,
        note = this,
        title = title,
        body = body,
        loading = false,
        readOnly = !editable
    )

    private fun readable(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
}
