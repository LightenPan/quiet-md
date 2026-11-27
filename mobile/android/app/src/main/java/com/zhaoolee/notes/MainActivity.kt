package com.zhaoolee.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.zhaoolee.notes.ui.NotesApp
import com.zhaoolee.notes.ui.theme.SmartisanNotesTheme
import com.zhaoolee.notes.viewmodel.SettingsViewModel
import com.zhaoolee.notes.viewmodel.NoteViewModel

class MainActivity : ComponentActivity() {
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val noteViewModel: NoteViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val isDarkMode by settingsViewModel.isDarkMode.collectAsState()
            val selectedTheme by settingsViewModel.selectedTheme.collectAsState()
            SmartisanNotesTheme(darkTheme = isDarkMode, themeStyle = selectedTheme) {
                NotesApp(
                    modifier = Modifier.fillMaxSize(),
                    noteViewModel = noteViewModel,
                    settingsViewModel = settingsViewModel
                )
            }
        }
    }

    override fun onStop() {
        noteViewModel.flush()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        noteViewModel.refresh()
    }
}
