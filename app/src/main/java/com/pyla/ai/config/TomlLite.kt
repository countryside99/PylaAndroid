package com.pyla.ai.config

import java.io.File

class TomlLite(private val root: Map<String, Any?>) {

    fun <T> get(path: String, default: T): T {
        val v = resolve(path) ?: return default
        @Suppress("UNCHECKED_CAST")
        return (v as? T) ?: default
    }

    fun opt(path: String): Any? = resolve(path)

    fun getString(path: String, default: String = ""): String {
        val v = resolve(path) ?: return default
        return v.toString()
    }

    fun getInt(path: String, default: Int = 0): Int {
        return when (val v = resolve(path)) {
            is Number -> v.toInt()
            is String -> v.toDoubleOrNull()?.toInt() ?: default
            null -> default
            else -> default
        }
    }

    fun getDouble(path: String, default: Double = 0.0): Double {
        return when (val v = resolve(path)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: default
            null -> default
            else -> default
        }
    }

    fun getBool(path: String, default: Boolean = false): Boolean {
        return when (val v = resolve(path)) {
            is Boolean -> v
            is String -> v.trim().lowercase() in setOf("1", "true", "yes", "on")
            is Number -> v.toInt() != 0
            null -> default
            else -> default
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun getIntArray(path: String, default: List<Int> = emptyList()): List<Int> {
        val v = resolve(path) as? List<*> ?: return default
        return v.mapNotNull { (it as? Number)?.toInt() }
    }

    @Suppress("UNCHECKED_CAST")
    fun getDoubleArray(path: String, default: List<Double> = emptyList()): List<Double> {
        val v = resolve(path) as? List<*> ?: return default
        return v.mapNotNull { (it as? Number)?.toDouble() }
    }

    @Suppress("UNCHECKED_CAST")
    fun getStringList(path: String, default: List<String> = emptyList()): List<String> {
        val v = resolve(path) as? List<*> ?: return default
        return v.map { it.toString() }
    }

    private fun resolve(path: String): Any? {
        val parts = path.split('.')
        var cur: Any? = root
        for (p in parts) {
            cur = (cur as? Map<*, *>)?.get(p) ?: return null
        }
        return cur
    }

    companion object {
        fun parse(file: File): TomlLite {
            if (!file.exists()) {
                android.util.Log.w("PylaToml", "Config file missing, using defaults: ${file.absolutePath}")
                return TomlLite(emptyMap())
            }
            return try {
                TomlLite(TomlParser.parse(file.readText()))
            } catch (t: Throwable) {
                android.util.Log.e("PylaToml", "Failed to parse ${file.absolutePath}: ${t.message}")
                TomlLite(emptyMap())
            }
        }
        fun parse(text: String): TomlLite = TomlLite(TomlParser.parse(text))
    }
}