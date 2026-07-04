package com.pyla.ai.config

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object AssetBundler {

    private const val TAG = "PylaAssets"
    private const val PARENT = "pyla"
    private const val VERSION_FILE = "pyla_assets_version.txt"
    private const val ASSETS_VERSION = 7

    fun ensureExtracted(context: Context): File {
        val rootDir = File(context.filesDir, PARENT)
        if (!rootDir.exists()) rootDir.mkdirs()
        val versionFile = File(rootDir, VERSION_FILE)
        if (versionFile.exists() && versionFile.readText().trim().toIntOrNull() == ASSETS_VERSION) {
            return rootDir
        }
        Log.i(TAG, "First-run extraction of bundled Pyla assets -> ${rootDir.absolutePath}")

        copyAssetDir(context, PARENT, rootDir)
        val sane = File(rootDir, "cfg/general_config.toml").exists()
        if (!sane) {
            Log.e(TAG, "Extraction finished but cfg/general_config.toml is missing, the APK was " +
                "built without Pyla assets. Rebuild so the copyPylaAssets Gradle task runs.")
            com.pyla.ai.engine.BotStatus.error("APK has no bundled assets, rebuild the app")

            return rootDir
        }
        versionFile.writeText(ASSETS_VERSION.toString())
        return rootDir
    }

    private fun copyAssetDir(context: Context, assetPath: String, outDir: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            if (assetPath.isEmpty()) return
            copyAssetFile(context, assetPath, outDir)
            return
        }
        if (!outDir.exists()) outDir.mkdirs()
        for (child in children) {
            val childAssetPath = if (assetPath.isEmpty()) child else "$assetPath/$child"
            val subList = context.assets.list(childAssetPath) ?: emptyArray()
            if (subList.isEmpty()) {
                copyAssetFile(context, childAssetPath, outDir)
            } else {
                val childOut = File(outDir, child)
                copyAssetDir(context, childAssetPath, childOut)
            }
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, outDir: File) {

        val rawName = File(assetPath).name
        val dot = rawName.lastIndexOf('.')
        val name = if (dot >= 0) rawName.substring(0, dot) + rawName.substring(dot).lowercase() else rawName
        val out = File(outDir, name)
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Skipping $assetPath: ${e.message}")
        }
    }

    fun resolvePath(rootDir: File, relative: String): File = File(rootDir, relative)
}