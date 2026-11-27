package com.zhaoolee.notes.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhaoolee.notes.ui.theme.ThemeStyle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore("settings")

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Context = application.applicationContext
    private object Keys {
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val THEME = stringPreferencesKey("theme_style")
        val FOOTER = stringPreferencesKey("footer_text")
    }
    val isDarkMode = context.dataStore.data.map { it[Keys.DARK_MODE] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val selectedTheme = context.dataStore.data.map {
        try { ThemeStyle.valueOf(it[Keys.THEME] ?: "WARM_PAPER") } catch (_: Exception) { ThemeStyle.WARM_PAPER }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeStyle.WARM_PAPER)
    val footerText = context.dataStore.data.map { it[Keys.FOOTER] ?: "锤子笔记" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "锤子笔记")

    fun setDarkMode(e: Boolean) { viewModelScope.launch { context.dataStore.edit { it[Keys.DARK_MODE] = e } } }
    fun setThemeStyle(s: ThemeStyle) { viewModelScope.launch { context.dataStore.edit { it[Keys.THEME] = s.name } } }
    fun setFooterText(t: String) { viewModelScope.launch { context.dataStore.edit { it[Keys.FOOTER] = t } } }

}
