package com.zhaoolee.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhaoolee.notes.ui.screens.NoteEditScreen
import com.zhaoolee.notes.ui.screens.NoteListScreen
import com.zhaoolee.notes.ui.screens.SettingsScreen
import com.zhaoolee.notes.viewmodel.NoteViewModel
import com.zhaoolee.notes.viewmodel.NutstoreViewModel
import com.zhaoolee.notes.viewmodel.SettingsViewModel

private const val SCREEN_LIST = "list"
private const val SCREEN_EDIT = "edit"
private const val SCREEN_SETTINGS = "settings"

@Composable
fun NotesApp(
    modifier: Modifier = Modifier,
    noteViewModel: NoteViewModel,
    settingsViewModel: SettingsViewModel
) {
    val nutstoreViewModel: NutstoreViewModel = viewModel()
    val footerText by settingsViewModel.footerText.collectAsState()
    val editorState by noteViewModel.editor.collectAsState()
    var currentScreen by rememberSaveable { mutableStateOf(SCREEN_LIST) }
    var editPath by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(currentScreen, editPath) {
        if (currentScreen == SCREEN_EDIT) {
            val path = editPath ?: return@LaunchedEffect
            val open = noteViewModel.editor.value
            if (open?.relativePath != path && open?.note?.relativePath != path) {
                noteViewModel.openNote(path)
            }
        }
    }

    LaunchedEffect(editorState?.relativePath, currentScreen) {
        val path = editorState?.relativePath
        if (currentScreen == SCREEN_EDIT && !path.isNullOrBlank() && path != editPath) {
            editPath = path
        }
    }

    BackHandler(enabled = currentScreen != SCREEN_LIST) {
        when (currentScreen) {
            SCREEN_EDIT -> noteViewModel.closeEditor {
                editPath = null
                currentScreen = SCREEN_LIST
            }
            SCREEN_SETTINGS -> {
                noteViewModel.refresh()
                currentScreen = SCREEN_LIST
            }
        }
    }

    when (currentScreen) {
        SCREEN_LIST -> NoteListScreen(
            modifier = modifier,
            viewModel = noteViewModel,
            onNoteClick = { note ->
                editPath = note.relativePath
                currentScreen = SCREEN_EDIT
            },
            onNewNote = {
                noteViewModel.createNote { path ->
                    editPath = path
                    currentScreen = SCREEN_EDIT
                }
            },
            onSettingsClick = { currentScreen = SCREEN_SETTINGS }
        )
        SCREEN_EDIT -> NoteEditScreen(
            modifier = modifier,
            viewModel = noteViewModel,
            footerText = footerText,
            onMoved = { editPath = it },
            onBack = {
                noteViewModel.closeEditor {
                    editPath = null
                    currentScreen = SCREEN_LIST
                }
            }
        )
        SCREEN_SETTINGS -> SettingsScreen(
            modifier = modifier,
            settingsViewModel = settingsViewModel,
            noteViewModel = noteViewModel,
            nutstoreViewModel = nutstoreViewModel,
            onBack = {
                noteViewModel.refresh()
                currentScreen = SCREEN_LIST
            }
        )
    }
}
