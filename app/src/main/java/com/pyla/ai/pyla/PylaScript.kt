package com.pyla.ai.pyla

import android.content.Context
import android.util.Log
import com.pyla.ai.config.PylaConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PlaystyleMeta(
    val filename: String,
    val name: String,
    val description: String,
    val brawlers: List<String>,
    val gamemodes: List<String>,
    val author: String,
) {
    val displayName: String get() = if (name.isNotBlank()) name else filename.removeSuffix(".pyla")
}

/**
 * A parsed + compiled .pyla playstyle. Mirrors the PC interpret_pyla_code flow: the body is
 * executed against a per-frame context and the resulting `movement` global is read back.
 */
class PylaScript(
    val meta: PlaystyleMeta,
    private val statements: List<Stmt>,
) {
    companion object {
        private const val TAG = "PylaScript"

        fun parseContent(filename: String, content: String): PylaScript {
            val (metaJson, body) = splitHeader(content)
            val meta = parseMeta(filename, metaJson)
            val toks = PyLexer(body).tokenize()
            val statements = PyParser(toks).parseModule()
            return PylaScript(meta, statements)
        }

        fun loadFile(file: File): PylaScript = parseContent(file.name, file.readText())

        /** Reads only the metadata header of a .pyla file (no body compilation). */
        fun readMeta(file: File): PlaystyleMeta {
            return try {
                val (metaJson, _) = splitHeader(file.readText())
                parseMeta(file.name, metaJson)
            } catch (t: Throwable) {
                PlaystyleMeta(file.name, file.name.removeSuffix(".pyla"), "", listOf("all"), listOf("all"), "")
            }
        }

        private fun splitHeader(content: String): Pair<String?, String> {
            val lines = content.split("\n")
            var idx = 0
            while (idx < lines.size && lines[idx].isBlank()) idx++
            if (idx < lines.size && lines[idx].trimStart().startsWith("{")) {
                val header = lines[idx].trim()
                val body = lines.subList(idx + 1, lines.size).joinToString("\n")
                return header to body
            }
            return null to content
        }

        private fun parseMeta(filename: String, metaJson: String?): PlaystyleMeta {
            if (metaJson == null) {
                return PlaystyleMeta(filename, filename.removeSuffix(".pyla"), "", listOf("all"), listOf("all"), "")
            }
            return try {
                val o = JSONObject(metaJson)
                PlaystyleMeta(
                    filename = filename,
                    name = o.optString("name", filename.removeSuffix(".pyla")),
                    description = o.optString("description", ""),
                    brawlers = jsonList(o.optJSONArray("brawlers")),
                    gamemodes = jsonList(o.optJSONArray("gamemodes")),
                    author = o.optString("author", ""),
                )
            } catch (t: Throwable) {
                PlaystyleMeta(filename, filename.removeSuffix(".pyla"), "", listOf("all"), listOf("all"), "")
            }
        }

        private fun jsonList(arr: JSONArray?): List<String> {
            if (arr == null) return listOf("all")
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) out.add(arr.optString(i))
            return if (out.isEmpty()) listOf("all") else out
        }
    }

    /**
     * Executes the script with the given context. Returns the `movement` value
     * (PyTuple/PyList/PyNone/etc.) or null if the script errored or produced nothing.
     */
    fun run(context: Map<String, Any>): Any? {
        val globals = PyInterpreter.buildGlobals(context)
        val interp = PyInterpreter(globals)
        return try {
            interp.execBlock(statements, globals)
            globals.vars["movement"]
        } catch (r: PyRaiseException) {
            Log.w(TAG, "playstyle '${meta.filename}' raised: ${r.value}")
            null
        } catch (e: PyRuntimeError) {
            Log.w(TAG, "playstyle '${meta.filename}' runtime error: ${e.message}")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "playstyle '${meta.filename}' failed: ${t.message}")
            null
        }
    }
}

/** Discovers and loads .pyla playstyles from the extracted assets / user import directory. */
object PlaystyleRegistry {

    private const val TAG = "PlaystyleRegistry"
    const val DEFAULT_PLAYSTYLE = "universal_smart_v5_Slarckvul_Eddition.pyla"

    fun playstylesDir(): File {
        val dir = PylaConfig.resolve("playstyles")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listMeta(): List<PlaystyleMeta> {
        val dir = playstylesDir()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".pyla") } ?: return emptyList()
        return files.sortedBy { it.name.lowercase() }.map { PylaScript.readMeta(it) }
    }

    /**
     * Names of playstyles that ship with the app (present in the bundled assets). These are the
     * original PylaAI playstyles and must not be deletable by the user. Anything not in this set
     * was imported by the user and may be removed.
     */
    fun bundledNames(context: Context): Set<String> =
        try {
            context.assets.list("pyla/playstyles")
                ?.filter { it.endsWith(".pyla") }
                ?.toSet() ?: emptySet()
        } catch (t: Throwable) {
            Log.w(TAG, "could not list bundled playstyles: ${t.message}")
            emptySet()
        }

    fun isBundled(context: Context, filename: String): Boolean = filename in bundledNames(context)

    fun load(filename: String): PylaScript? {
        val file = File(playstylesDir(), filename)
        if (!file.exists()) {
            Log.w(TAG, "playstyle file not found: $filename")
            return null
        }
        return try {
            PylaScript.loadFile(file)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to compile $filename: ${t.message}")
            null
        }
    }

    /** Imports raw .pyla content under the given filename. Returns the stored filename or null. */
    fun importContent(originalName: String, content: String): String? {
        return try {
            // Validate that it compiles before saving.
            PylaScript.parseContent(originalName, content)
            var name = sanitizeName(originalName)
            if (!name.endsWith(".pyla")) name += ".pyla"
            val out = File(playstylesDir(), name)
            out.writeText(content)
            Log.i(TAG, "imported playstyle: $name")
            name
        } catch (t: Throwable) {
            Log.w(TAG, "import failed for $originalName: ${t.message}")
            null
        }
    }

    fun delete(filename: String): Boolean {
        val f = File(playstylesDir(), filename)
        return f.exists() && f.delete()
    }

    private fun sanitizeName(name: String): String {
        val base = File(name).name
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
