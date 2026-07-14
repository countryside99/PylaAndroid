package com.pyla.ai.config

import android.content.Context
import com.pyla.ai.PylaApp

object PylaUserSettings {
    private const val PREFS_NAME = "pyla_overrides"
    private const val DEFAULTS_PREFIX = "dflt::"

    private fun prefs(): android.content.SharedPreferences =
        PylaApp.ctx().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun has(relativePath: String, configKey: String): Boolean =
        prefs().contains(cacheKey(relativePath, configKey))

    fun getString(relativePath: String, configKey: String, default: String? = null): String? =
        PylaConfig.load(relativePath).opt(configKey)?.toString() ?: default

    fun set(relativePath: String, configKey: String, value: String) {
        prefs().edit().putString(cacheKey(relativePath, configKey), value).apply()
        writeTomlDirect(relativePath, configKey, value)
        PylaConfig.invalidate(relativePath)
    }

    fun remove(relativePath: String, configKey: String) {
        val defaultVal = getCachedDefault(relativePath, configKey) ?: return
        writeTomlDirect(relativePath, configKey, defaultVal)
        prefs().edit().remove(cacheKey(relativePath, configKey)).apply()
        PylaConfig.invalidate(relativePath)
    }

    fun clearAll() {
        val allKeys = prefs().all.keys.toList()
        val editor = prefs().edit()
        for (k in allKeys) {
            if (k.startsWith(DEFAULTS_PREFIX)) continue
            val parts = k.split("::", limit = 2)
            if (parts.size != 2) continue
            val relativePath = parts[0]
            val configKey = parts[1]
            val defaultVal = getCachedDefault(relativePath, configKey) ?: continue
            writeTomlDirect(relativePath, configKey, defaultVal)
            editor.remove(cacheKey(relativePath, configKey))
        }
        editor.apply()
        PylaConfig.invalidateAll()
    }

    fun getDefaultString(relativePath: String, configKey: String): String? {
        return getCachedDefault(relativePath, configKey)
    }

    private fun writeTomlDirect(relativePath: String, key: String, value: String) {
        try {
            val file = PylaConfig.resolve(relativePath)
            if (!file.exists()) {
                android.util.Log.w("PylaSettings", "TOML file not found: ${file.absolutePath}")
                return
            }
            val content = file.readText()
            val escapedKey = Regex.escape(key)
            val regex = Regex("""(^|\n)([ \t]*${escapedKey}[ \t]*=[ \t]*)[^\n\r]*""")
            val formatted = formatTomlValue(value)
            val replaced = regex.replace(content) { m: MatchResult ->
                "${m.groupValues[1]}${m.groupValues[2]}$formatted"
            }
            file.writeText(replaced)
        } catch (e: Exception) {
            android.util.Log.e("PylaSettings", "write failed $relativePath::$key: ${e.message}")
        }
    }

    private fun formatTomlValue(v: String): String {
        if (v == "true" || v == "false") return v
        v.toIntOrNull()?.let { return v }
        v.toDoubleOrNull()?.let { return v }
        return "\"$v\""
    }

    private fun getCachedDefault(relativePath: String, configKey: String): String? {
        val defKey = DEFAULTS_PREFIX + relativePath + "::" + configKey
        val existing = prefs().getString(defKey, null)
        if (existing != null) return existing
        try {
            val file = PylaConfig.resolve(relativePath)
            if (!file.exists()) return null
            val toml = TomlLite.parse(file)
            val raw = toml.opt(configKey)?.toString() ?: return null
            prefs().edit().putString(defKey, raw).apply()
            return raw
        } catch (_: Exception) { return null }
    }

    private fun cacheKey(relativePath: String, configKey: String): String =
        "$relativePath::$configKey"
}
