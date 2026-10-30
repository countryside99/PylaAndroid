package com.pyla.ai.pyla

import android.content.Context
import com.pyla.ai.engine.PylaLog as Log
import com.pyla.ai.diagnostics.DiagnosticLog
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

/** Terminal outcome of one bounded playstyle execution. Fixed set — safe to log or persist. */
enum class PylaRunStatus { SUCCESS, RUNTIME_ERROR, BUDGET_EXCEEDED, CANCELLED }

/**
 * Outcome of one bounded run.
 *
 * [movement] is the script's `movement` global, and only for [PylaRunStatus.SUCCESS]; `null` means
 * the key was absent (or the script produced no movement), which is distinct from a failed run
 * because [status] is never SUCCESS for a failure. A legitimate `PyNone` movement stays non-null.
 * [steps] and [elapsedNanos] are the budget counters at the moment the run stopped.
 */
data class PylaRunResult(
    val status: PylaRunStatus,
    val movement: Any?,
    val steps: Long,
    val elapsedNanos: Long,
)

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
                DiagnosticLog.failure("playstyle", "metadata_read", "metadata_read_failed", t)
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
                DiagnosticLog.failure("playstyle", "metadata_parse", "metadata_parse_failed", t)
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
     * (PyTuple/PyList/PyNone/etc.) or null if the script errored, was cancelled, ran out of budget
     * or produced nothing.
     *
     * Compatibility wrapper over [runBounded]: only [PylaRunStatus.SUCCESS] exposes a value.
     */
    fun run(context: Map<String, Any>): Any? {
        val result = runBounded(context)
        return if (result.status == PylaRunStatus.SUCCESS) result.movement else null
    }

    /**
     * Executes the script under a finite, deterministic execution budget and reports an explicit
     * outcome instead of collapsing failure into a null movement.
     *
     * The budget is armed per call, so two successive runs never share counters, and the active
     * budget binding is removed from the thread on every exit path (success, script error, budget
     * stop). [cancelled] is polled at entry, around every native call, periodically during
     * interpretation and once more after the script completes; thread interruption is treated the
     * same way. Neither the deadline nor cancellation can be extended by script code.
     */
    fun runBounded(
        context: Map<String, Any>,
        cancelled: () -> Boolean = { false },
    ): PylaRunResult = runBoundedWith(context, PyExecutionBudget(cancelled = cancelled))

    /** Budget/clock seam for focused tests. Production callers use [runBounded]. */
    internal fun runBoundedWith(context: Map<String, Any>, budget: PyExecutionBudget): PylaRunResult {
        val globals = PyInterpreter.buildGlobals(context)
        val interp = PyInterpreter(globals, budget)
        var status = PylaRunStatus.SUCCESS
        var movement: Any? = null
        try {
            budget.start()
            PyBudgetContext.withBudget(budget) {
                budget.tick() // entry: cancellation / deadline / interruption
                interp.execBlock(statements, globals)
                budget.completionCheck() // a cancellation that lands at the end still wins
                movement = globals.vars["movement"]
            }
        } catch (b: PyBudgetExceededException) {
            status = if (b.kind == PyBudgetKind.CANCELLED) PylaRunStatus.CANCELLED else PylaRunStatus.BUDGET_EXCEEDED
            DiagnosticLog.failure("playstyle", "execute", "budget_${b.kind.name.lowercase(java.util.Locale.ROOT)}", b)
        } catch (r: PyRaiseException) {
            status = PylaRunStatus.RUNTIME_ERROR
            DiagnosticLog.failure("playstyle", "execute", "script_raise", r)
        } catch (e: PyRuntimeError) {
            status = PylaRunStatus.RUNTIME_ERROR
            DiagnosticLog.failure("playstyle", "execute", "runtime_error", e)
        } catch (t: Throwable) {
            // Includes anything unexpected escaping a native callback; never inspect its message.
            status = PylaRunStatus.RUNTIME_ERROR
            DiagnosticLog.failure("playstyle", "execute", "callback_exception", t)
        }
        if (status != PylaRunStatus.SUCCESS) {
            // Fixed status/type only: no script exception value or message may reach the log.
            runCatching { Log.w(TAG, "playstyle status=${status.name}") }
        }
        return PylaRunResult(
            status = status,
            movement = if (status == PylaRunStatus.SUCCESS) movement else null,
            steps = budget.steps,
            elapsedNanos = budget.elapsedNanos(),
        )
    }
}

/** Discovers and loads .pyla playstyles from the extracted assets / user import directory. */
object PlaystyleRegistry {

    private const val TAG = "PlaystyleRegistry"
    const val DEFAULT_PLAYSTYLE = "universal_smart_v5_Slarckvul_Eddition.pyla"
    private val NAME_SANITIZE_REGEX = Regex("[^A-Za-z0-9._-]")

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
            DiagnosticLog.failure("playstyle", "registry_list", "bundled_list_failed", t)
            Log.w(TAG, "could not list bundled playstyles", t)
            emptySet()
        }

    fun isBundled(context: Context, filename: String): Boolean = filename in bundledNames(context)

    fun load(filename: String): PylaScript? {
        val file = File(playstylesDir(), filename)
        if (!file.exists()) {
            DiagnosticLog.failure("playstyle", "registry_load", "file_missing")
            Log.w(TAG, "playstyle file not found")
            return null
        }
        return try {
            PylaScript.loadFile(file)
        } catch (t: Throwable) {
            DiagnosticLog.failure("playstyle", "registry_load", "compile_failed", t)
            Log.w(TAG, "failed to compile playstyle", t)
            null
        }
    }

    /** The document importer owns/closes its bounded input and never overwrites an existing style. */
    fun importDocument(originalName: String, input: java.io.InputStream?): PlaystyleImportResult {
        val directory = try {
            playstylesDir()
        } catch (_: Exception) {
            try { input?.close() } catch (_: Exception) { }
            return PlaystyleImportResult.Failure(PlaystyleImportFailure.WRITE_FAILED)
        }
        return PlaystyleImporter.importFrom(input, originalName, directory) { filename, content ->
            PylaScript.parseContent(filename, content)
        }
    }

    /** Compatibility entry point for callers that already have UTF-8 text. */
    fun importContent(originalName: String, content: String): String? =
        when (val result = importDocument(originalName, content.byteInputStream(Charsets.UTF_8))) {
            is PlaystyleImportResult.Success -> result.filename
            is PlaystyleImportResult.Failure -> {
                DiagnosticLog.failure("playstyle", "registry_import", "import_${result.reason.name.lowercase(java.util.Locale.ROOT)}")
                null
            }
        }

    fun delete(filename: String): Boolean {
        val f = File(playstylesDir(), filename)
        return f.exists() && f.delete()
    }

    private fun sanitizeName(name: String): String {
        val base = File(name).name
        return base.replace(NAME_SANITIZE_REGEX, "_")
    }
}
