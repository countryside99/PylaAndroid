package com.pyla.ai.overlay

import android.content.Context
import android.provider.Settings

object OverlayPreferences {
    private const val PREFS = "pyla"
    private const val KEY_ENABLED = "floating_overlay_enabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context.applicationContext)
}
