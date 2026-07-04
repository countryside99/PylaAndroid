package com.pyla.ai.config

import android.content.Context
import com.pyla.ai.PylaApp
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object PylaConfig {
    private var rootDir: File? = null
    private val cache = ConcurrentHashMap<String, TomlLite>()

    fun init(context: Context): File {
        val dir = AssetBundler.ensureExtracted(context)
        rootDir = dir
        return dir
    }

    fun isReady(): Boolean = rootDir != null

    fun root(): File = rootDir ?: AssetBundler.ensureExtracted(PylaApp.ctx())

    fun resolve(relative: String): File = File(root(), relative)

    fun load(relative: String): TomlLite {
        return cache.computeIfAbsent(relative) {
            TomlLite.parse(resolve(relative))
        }
    }

    fun invalidate(relative: String) { cache.remove(relative) }
}