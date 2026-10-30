package com.pyla.ai.config

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Narrow retained-install migration; do not change generic settings' missing-key semantics. */
internal object PlaystyleSelectionFile {
    @Synchronized
    fun ensureKey(file: File, defaultPlaystyle: String) {
        // Android app mount namespaces alias /data/user/0 to /data/data. Reject
        // only a symlink leaf, not legitimate parent aliases of app-private storage.
        if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) {
            throw IOException("playstyle_config_unavailable")
        }
        val original = file.readText(Charsets.UTF_8)
        val normalized = original.removePrefix("\uFEFF")
        val containsKey = TomlLite.parse(normalized).opt("current_playstyle") != null
        if (containsKey && normalized == original) return
        val updated = if (containsKey) normalized else
            "current_playstyle = ${formatTomlValue(defaultPlaystyle)}\n" + normalized
        val staged = File.createTempFile(".playstyle-selection-", ".tmp", file.parentFile)
        try {
            FileOutputStream(staged).use { output ->
                output.write(updated.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            if (!staged.renameTo(file)) throw IOException("playstyle_config_write_failed")
        } finally {
            staged.delete()
        }
    }
}
