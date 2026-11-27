package com.zhaoolee.notes.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeStyle { WARM_PAPER, NIGHT_NOTE, APPLE_NOTES, BEAR_MINIMAL, TELEGRAPH }

data class NoteColorScheme(
    val background: androidx.compose.ui.graphics.Color, val surface: androidx.compose.ui.graphics.Color,
    val surfaceVariant: androidx.compose.ui.graphics.Color, val onBackground: androidx.compose.ui.graphics.Color,
    val onSurface: androidx.compose.ui.graphics.Color, val onSurfaceVariant: androidx.compose.ui.graphics.Color,
    val primary: androidx.compose.ui.graphics.Color, val primaryDark: androidx.compose.ui.graphics.Color,
    val onPrimary: androidx.compose.ui.graphics.Color, val secondary: androidx.compose.ui.graphics.Color,
    val outline: androidx.compose.ui.graphics.Color, val divider: androidx.compose.ui.graphics.Color,
    val dateText: androidx.compose.ui.graphics.Color, val categoryBg: androidx.compose.ui.graphics.Color,
    val destructive: androidx.compose.ui.graphics.Color
)

private fun cs(b: androidx.compose.ui.graphics.Color, s: androidx.compose.ui.graphics.Color, sv: androidx.compose.ui.graphics.Color,
               ob: androidx.compose.ui.graphics.Color, os: androidx.compose.ui.graphics.Color, osv: androidx.compose.ui.graphics.Color,
               p: androidx.compose.ui.graphics.Color, pd: androidx.compose.ui.graphics.Color, op: androidx.compose.ui.graphics.Color,
               sc: androidx.compose.ui.graphics.Color, o: androidx.compose.ui.graphics.Color, d: androidx.compose.ui.graphics.Color,
               dt: androidx.compose.ui.graphics.Color, cb: androidx.compose.ui.graphics.Color, dr: androidx.compose.ui.graphics.Color) =
    NoteColorScheme(b, s, sv, ob, os, osv, p, pd, op, sc, o, d, dt, cb, dr)

private fun getColors(style: ThemeStyle, dark: Boolean): NoteColorScheme {
    if (dark) return cs(NightNoteColors.background, NightNoteColors.surface, NightNoteColors.surfaceVariant,
        NightNoteColors.onBackground, NightNoteColors.onSurface, NightNoteColors.onSurfaceVariant,
        NightNoteColors.primary, NightNoteColors.primaryDark, NightNoteColors.onPrimary,
        NightNoteColors.secondary, NightNoteColors.outline, NightNoteColors.divider,
        NightNoteColors.dateText, NightNoteColors.categoryBg, NightNoteColors.destructive)
    return when (style) {
        ThemeStyle.WARM_PAPER -> cs(WarmPaperColors.background, WarmPaperColors.surface, WarmPaperColors.surfaceVariant,
            WarmPaperColors.onBackground, WarmPaperColors.onSurface, WarmPaperColors.onSurfaceVariant,
            WarmPaperColors.primary, WarmPaperColors.primaryDark, WarmPaperColors.onPrimary,
            WarmPaperColors.secondary, WarmPaperColors.outline, WarmPaperColors.divider,
            WarmPaperColors.dateText, WarmPaperColors.categoryBg, WarmPaperColors.destructive)
        ThemeStyle.APPLE_NOTES -> cs(AppleNotesColors.background, AppleNotesColors.surface, AppleNotesColors.surfaceVariant,
            AppleNotesColors.onBackground, AppleNotesColors.onSurface, AppleNotesColors.onSurfaceVariant,
            AppleNotesColors.primary, AppleNotesColors.primaryDark, AppleNotesColors.onPrimary,
            AppleNotesColors.secondary, AppleNotesColors.outline, AppleNotesColors.divider,
            AppleNotesColors.dateText, AppleNotesColors.categoryBg, AppleNotesColors.destructive)
        ThemeStyle.BEAR_MINIMAL -> cs(BearMinimalColors.background, BearMinimalColors.surface, BearMinimalColors.surfaceVariant,
            BearMinimalColors.onBackground, BearMinimalColors.onSurface, BearMinimalColors.onSurfaceVariant,
            BearMinimalColors.primary, BearMinimalColors.primaryDark, BearMinimalColors.onPrimary,
            BearMinimalColors.secondary, BearMinimalColors.outline, BearMinimalColors.divider,
            BearMinimalColors.dateText, BearMinimalColors.categoryBg, BearMinimalColors.destructive)
        ThemeStyle.TELEGRAPH -> cs(TelegraphColors.background, TelegraphColors.surface, TelegraphColors.surfaceVariant,
            TelegraphColors.onBackground, TelegraphColors.onSurface, TelegraphColors.onSurfaceVariant,
            TelegraphColors.primary, TelegraphColors.primaryDark, TelegraphColors.onPrimary,
            TelegraphColors.secondary, TelegraphColors.outline, TelegraphColors.divider,
            TelegraphColors.dateText, TelegraphColors.categoryBg, TelegraphColors.destructive)
        ThemeStyle.NIGHT_NOTE -> cs(NightNoteColors.background, NightNoteColors.surface, NightNoteColors.surfaceVariant,
            NightNoteColors.onBackground, NightNoteColors.onSurface, NightNoteColors.onSurfaceVariant,
            NightNoteColors.primary, NightNoteColors.primaryDark, NightNoteColors.onPrimary,
            NightNoteColors.secondary, NightNoteColors.outline, NightNoteColors.divider,
            NightNoteColors.dateText, NightNoteColors.categoryBg, NightNoteColors.destructive)
    }
}

@Composable
fun SmartisanNotesTheme(darkTheme: Boolean = isSystemInDarkTheme(), themeStyle: ThemeStyle = ThemeStyle.WARM_PAPER, content: @Composable () -> Unit) {
    val c = getColors(themeStyle, darkTheme)
    val scheme = if (darkTheme) darkColorScheme(primary = c.primary, onPrimary = c.onPrimary, secondary = c.secondary,
        background = c.background, surface = c.surface, surfaceVariant = c.surfaceVariant,
        onBackground = c.onBackground, onSurface = c.onSurface, onSurfaceVariant = c.onSurfaceVariant,
        outline = c.outline, error = c.destructive)
    else lightColorScheme(primary = c.primary, onPrimary = c.onPrimary, secondary = c.secondary,
        background = c.background, surface = c.surface, surfaceVariant = c.surfaceVariant,
        onBackground = c.onBackground, onSurface = c.onSurface, onSurfaceVariant = c.onSurfaceVariant,
        outline = c.outline, error = c.destructive)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = c.background.toArgb()
            window.navigationBarColor = c.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = scheme, typography = SmartisanTypography, content = content)
}
