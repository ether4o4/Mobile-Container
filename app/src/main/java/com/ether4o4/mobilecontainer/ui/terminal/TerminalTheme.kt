package com.ether4o4.mobilecontainer.ui.terminal

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Changeable terminal color theme. The shell background and default text color
 * are user-selectable and persist across launches via SharedPreferences.
 */
data class TerminalTheme(
    val name: String,
    val background: Color,
    val foreground: Color
)

object TerminalThemeStore {
    private const val PREFS = "mc_terminal_theme"
    private const val KEY_INDEX = "theme_index"

    val presets = listOf(
        TerminalTheme("Midnight", Color(0xFF0D0D0F), Color(0xFFE6E6E6)),
        TerminalTheme("Matrix", Color(0xFF000000), Color(0xFF33FF33)),
        TerminalTheme("Amber", Color(0xFF1A0F00), Color(0xFFFFB454)),
        TerminalTheme("Solarized", Color(0xFF002B36), Color(0xFF93A1A1)),
        TerminalTheme("Dracula", Color(0xFF282A36), Color(0xFFF8F8F2)),
        TerminalTheme("Paper", Color(0xFFF5F5F0), Color(0xFF1A1A1E)),
        TerminalTheme("Nord", Color(0xFF2E3440), Color(0xFFD8DEE9)),
        TerminalTheme("Hot Pink", Color(0xFF1A0A12), Color(0xFFFF6EC7))
    )

    private val _themeIndex = MutableStateFlow(0)
    val themeIndex: StateFlow<Int> = _themeIndex

    private var initialized = false

    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _themeIndex.value = prefs.getInt(KEY_INDEX, 0).coerceIn(0, presets.lastIndex)
    }

    fun current(): TerminalTheme = presets[_themeIndex.value]

    fun select(ctx: Context, index: Int) {
        val clamped = index.coerceIn(0, presets.lastIndex)
        _themeIndex.value = clamped
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_INDEX, clamped)
            .apply()
    }

    fun cycle(ctx: Context) {
        val next = (_themeIndex.value + 1) % presets.size
        select(ctx, next)
    }
}
