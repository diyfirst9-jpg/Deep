package com.firstt175.deepdrop.session

import android.content.Context

/**
 * Icon-arrangement styles for the in-app launcher grid (GameLauncherScreen).
 *
 * - [LIST]: single-column rows, one app per line (original layout).
 * - [GRID]: icon grid, several apps per row.
 * - [SWITCH_HORIZONTAL]: a horizontally scrolling row of large tiles, styled
 *   after the Nintendo Switch home menu — swipe sideways through big icons
 *   instead of scrolling a vertical list.
 */
enum class LauncherLayout {
    LIST,
    GRID,
    SWITCH_HORIZONTAL,
}

/** Persists the chosen [LauncherLayout] across app restarts. */
object LauncherLayoutStore {
    private const val PREFS = "launcher_layout"
    private const val KEY_MODE = "mode"

    fun load(ctx: Context): LauncherLayout {
        val stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
        return runCatching { LauncherLayout.valueOf(stored ?: "") }
            .getOrDefault(LauncherLayout.SWITCH_HORIZONTAL)
    }

    fun save(ctx: Context, mode: LauncherLayout) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }
}
