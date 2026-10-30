package com.pyla.ai.config

import android.content.Context
import com.pyla.ai.PylaApp
import com.pyla.ai.pyla.PlaystyleRegistry

/**
 * Production wiring of [PlaystyleSelectionResolver]: one effective playstyle shared by
 * `PlaystylesScreen` (display/select/import/delete) and the engine start path
 * (`BotEngine.bootstrapAndRun`). The authoritative store is `current_playstyle` in
 * `cfg/bot_config.toml`, written through [PylaUserSettings] so the pristine default is
 * snapshotted before each write; the legacy `pyla`/`playstyle` preference stays honored
 * for backward compatibility and is kept mirrored, never contradictory.
 */
object PlaystyleSelection {

    const val CONFIG_PATH = "cfg/bot_config.toml"
    const val CONFIG_KEY = "current_playstyle"
    private const val LEGACY_PREFS = "pyla"
    private const val LEGACY_KEY = "playstyle"

    private val source: PlaystyleSelectionSource by lazy {
        object : PlaystyleSelectionSource {
            override val defaultPlaystyle: String
                get() = PlaystyleRegistry.DEFAULT_PLAYSTYLE

            override fun readLegacyPreference(): String? =
                prefs().getString(LEGACY_KEY, null)

            override fun readTomlSelection(): String? =
                PylaUserSettings.getString(CONFIG_PATH, CONFIG_KEY, null)

            override fun writeTomlSelection(value: String) {
                // Retained installs predate this key while keeping the same asset version.
                // Seed its shipped default before the normal setter snapshots defaults.
                PlaystyleSelectionFile.ensureKey(PylaConfig.resolve(CONFIG_PATH), defaultPlaystyle)
                PylaUserSettings.set(CONFIG_PATH, CONFIG_KEY, value)
                if (PylaUserSettings.getString(CONFIG_PATH, CONFIG_KEY, null) != value) {
                    throw java.io.IOException("playstyle_selection_write_failed")
                }
            }

            override fun writeLegacyPreference(value: String) {
                prefs().edit().putString(LEGACY_KEY, value).apply()
            }

            override fun playstyleFileExists(filename: String): Boolean =
                isContainedPlaystyleFile(PlaystyleRegistry.playstylesDir(), filename)
        }
    }

    fun effectivePlaystyle(): String = resolver().effectivePlaystyle()

    fun select(filename: String): String = resolver().select(filename)

    fun onPlaystyleDeleted(filename: String): String = resolver().onPlaystyleDeleted(filename)

    private fun resolver(): PlaystyleSelectionResolver = PlaystyleSelectionResolver(source)

    private fun prefs(): android.content.SharedPreferences =
        PylaApp.ctx().getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
}
