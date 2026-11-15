package com.pyla.ai.diagnostics

import java.util.UUID

/** Bounded process-session text, with sanitization before RAM retention.
 * Observation only: no disk, network, full Logcat, image/OCR data or callbacks.
 * Short synchronized sections protect the ring; diagnostic failures are isolated.
 * UTF-8 bounds cover entries, their separators, and the complete report header.
 */
object DiagnosticLog {
    const val MAX_ENTRIES = 2_000
    const val MAX_BYTES = 256 * 1024
    const val MAX_CAUSE_DEPTH = 8
    const val MAX_MESSAGE_CHARS = 2_000
    private const val HEADER_RESERVE = 512
    private const val MAX_DEVICE_CHARS = 200
    @Volatile private var deviceProfile: String = ""
    private const val MAX_CHANNELS = 128
    private val lock = Any()
    private val entries = ArrayDeque<String>()
    private var bytes = 0
    private var sequence = 0L
    private var droppedEntries = 0L
    private var foldedEntries = 0L
    private val startNanos = System.nanoTime()
    private data class Channel(var detail: String, var emittedMs: Long, var suppressed: Long = 0)
    private val channels = LinkedHashMap<String, Channel>(MAX_CHANNELS, 0.75f, true)

    /** Safe single-line rendering; unbounded input is omitted, not partially leaked. */
    fun safeText(raw: String, limit: Int = MAX_MESSAGE_CHARS): String = try {
        if (raw.length > 8_192) "[oversized text omitted]" else {
            val clean = buildString(raw.length) {
                for (c in raw) {
                    val t = Character.getType(c)
                    when {
                        c == '\n' || c == '\r' || c == '\t' -> append(' ')
                        c.code < 32 || c.code == 127 || t == Character.FORMAT.toInt() ||
                            t == Character.LINE_SEPARATOR.toInt() || t == Character.PARAGRAPH_SEPARATOR.toInt() -> Unit
                        else -> append(c)
                    }
                }
            }
            // Composed Authorization must be handled before generic key/value rules.
            var s = URL.replace(clean, "[redacted]")
            s = AUTH.replace(s, "[redacted]")
            s = COOKIE.replace(s, "[redacted]")
            s = BEARER.replace(s, "[redacted]")
            s = KEY.replace(s) { "${it.groupValues[1]}=[redacted]" }
            s = OPAQUE.replace(s, "[redacted]")
            s = EMAIL.replace(s, "[redacted]")
            s = IP.replace(s, "[redacted]")
            s = IPV6.replace(s, "[redacted]")
            s = PATH.replace(s, "[redacted]")
            s = PACKAGE.replace(s, "[redacted]")
            s = IDENTIFIER.replace(s, "[redacted]")
            val n = limit.coerceIn(0, MAX_MESSAGE_CHARS)
            if (s.length <= n) s.trim() else {
                val end = if (n > 0 && s[n - 1].isHighSurrogate()) n - 1 else n
                s.take(end) + "...[truncated]"
            }
        }
    } catch (_: Throwable) { "[diagnostic text omitted]" }

    fun record(level: String, tag: String, message: String, throwable: Throwable? = null) {
        try {
            val l = safeText(level, 16)
            val t = safeText(tag, 48)
            // Runtime summaries may contain model/device fields; the typed hooks suffice.
            val m = if (message.startsWith("Runtime profile:")) "Runtime profile selected" else safeText(message)
            val cause = if (throwable == null) "" else " exception=" + throwableTypes(throwable)
            emit("L|$l|$t", m + cause, "log:$l:$t", 10_000, changes = true)
        } catch (_: Throwable) { }
    }

    /** Keep transitions immediately; fold only identical repeated observations. */
    fun event(kind: String, detail: String) {
        try { emit("E|${safeText(kind, 48)}", safeText(detail), "event:${safeText(kind, 48)}", 10_000, true) }
        catch (_: Throwable) { }
    }

    /** Fixed workflow failure categories; no Throwable message or stack is retained. */
    fun failure(kind: String, phase: String, reason: String, throwable: Throwable? = null) {
        try {
            val channel = safeText(kind, 32)
            val safePhase = safeText(phase, 48)
            val safeReason = safeText(reason, 80)
            val detail = "phase=$safePhase reason=$safeReason" +
                if (throwable == null) "" else " exception=${throwableTypes(throwable)}"
            emit("E|failure.$channel", detail, "failure:$channel:$safePhase:$safeReason", 10_000, true)
        } catch (_: Throwable) { }
    }

    /** Workflow completion is never hidden by event folding. Does not imply game-visible effect. */
    fun terminal(kind: String, phase: String, result: String, reason: String) {
        try {
            val channel = safeText(kind, 32)
            val detail = "phase=${safeText(phase, 48)} result=${safeText(result, 32)} reason=${safeText(reason, 80)}"
            emit("E|terminal.$channel", detail, "terminal:$channel", 0, true)
        } catch (_: Throwable) { }
    }

    /** At most one sample per channel per interval, even if numeric details change. */
    fun sample(kind: String, detail: String, intervalMs: Long = 1_000) {
        try { emit("E|${safeText(kind, 48)}", safeText(detail), "sample:${safeText(kind, 48)}", intervalMs.coerceIn(100, 60_000), false) }
        catch (_: Throwable) { }
    }

    private fun emit(prefix: String, body: String, key: String, interval: Long, changes: Boolean) {
        synchronized(lock) {
            val elapsed = ((System.nanoTime() - startNanos) / 1_000_000).coerceAtLeast(0)
            val old = channels[key]
            if (old != null && elapsed - old.emittedMs < interval && (!changes || old.detail == body)) {
                old.suppressed++
                foldedEntries++
                return
            }
            val suppressed = old?.suppressed ?: 0
            channels[key] = Channel(body, elapsed)
            while (channels.size > MAX_CHANNELS) channels.remove(channels.keys.first())
            val suffix = if (suppressed > 0) " ($suppressed repeats folded)" else ""
            val line = "${++sequence}|$elapsed|$prefix $body$suffix"
            entries.addLast(line)
            // Debug builds mirror the already-sanitized line to Logcat for on-device development.
            if (com.pyla.ai.BuildConfig.DEBUG) try { android.util.Log.d("PylaDiag", line) } catch (_: Throwable) { }
            bytes += line.toByteArray(Charsets.UTF_8).size + 1
            while (entries.size > MAX_ENTRIES || bytes > MAX_BYTES - HEADER_RESERVE) {
                bytes -= entries.removeFirst().toByteArray(Charsets.UTF_8).size + 1
                droppedEntries++
            }
        }
    }

    /** No messages/stack frames from Throwable: they can contain arbitrary user content. */
    private fun throwableTypes(t: Throwable): String = try {
        val names = ArrayList<String>(MAX_CAUSE_DEPTH)
        var next: Throwable? = t
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        while (next != null && names.size < MAX_CAUSE_DEPTH && seen.add(next)) {
            names.add(safeText(next.javaClass.simpleName, 48))
            next = next.cause
        }
        names.joinToString(" <- ")
    } catch (_: Throwable) { "Throwable" }

    /**
     * Non-identifying compatibility facts (manufacturer, model, API, ABI, screen and capture size,
     * core count, RAM class). Deliberately no serial, Android ID, IMEI, account or network data.
     * Kept outside the ring so a long session cannot rotate it out of the report.
     */
    fun setDeviceProfile(profile: String) {
        deviceProfile = try {
            profile.filter { it.code in 32..126 && it != '#' }.take(MAX_DEVICE_CHARS).trim()
        } catch (_: Throwable) { "" }
    }

    fun snapshot(): DiagnosticSnapshot {
        val id = UUID.randomUUID().toString()
        val text = synchronized(lock) {
            "# pyla-diagnostics schema=1\n# report_id=$id\n# entries=${entries.size}\n" +
                "# diagnostic_revision=flow_logs_v1\n# dropped_entries=$droppedEntries\n# folded_entries=$foldedEntries\n" +
                (if (deviceProfile.isEmpty()) "" else "# device=$deviceProfile\n") +
                entries.joinToString("\n")
        }
        return DiagnosticSnapshot(id, text)
    }

    fun recentLines(limit: Int = 80): List<String> = try {
        synchronized(lock) { ArrayList(entries.takeLast(limit.coerceIn(0, MAX_ENTRIES))) }
    } catch (_: Throwable) { emptyList() }

    internal fun resetForTesting() = synchronized(lock) {
        entries.clear(); channels.clear(); bytes = 0; sequence = 0
        droppedEntries = 0; foldedEntries = 0; deviceProfile = ""
    }

    private val URL = Regex("""(?i)\b[a-z][a-z0-9+.\-]*://\S+""")
    private val AUTH = Regex("""(?i)\b(?:proxy-)?authorization["']?\s*[:=]\s*["']?(?:(?:bearer|basic)(?:\s+|%20))?[^\s,;"'}]+""")
    private val COOKIE = Regex("""(?i)\b(?:set-)?cookie["']?\s*[:=].*""")
    private val BEARER = Regex("""(?i)\bbearer(?:\s+|%20)[^\s,;"'}]+""")
    private val KEY = Regex("""(?i)\b([a-z0-9_\-]*(?:password|passwd|pwd|secret|token|api[_\-]?key|access[_\-]?key|private[_\-]?key|device[_\-]?key|credential|session|android[_\-]?id|serial|imei|account[_\-]?id|user[_\-]?id)[a-z0-9_\-]*)["']?\s*(?:[:=]|\s)\s*(?:"[^"]*"|'[^']*'|[^\s,;}]+)""")
    private val OPAQUE = Regex("""\b(?:eyJ[A-Za-z0-9_\-]+(?:\.[A-Za-z0-9_\-]+){1,2}|(?:gh[pousr]_|sk-|AIza)[A-Za-z0-9_\-]{8,})\b""")
    private val EMAIL = Regex("""\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b""")
    private val IP = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val IPV6 = Regex("""(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f:])""")
    private val PATH = Regex("""(?<![A-Za-z0-9_])(?:/(?:[^\s/]+/)*[^\s/]+|[A-Za-z]:\\[^\s]+)""")
    private val PACKAGE = Regex("""\b(?:com|org|net|io|android)(?:\.[a-zA-Z_][a-zA-Z0-9_]*){2,}\b""")
    private val IDENTIFIER = Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b|\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\b""")
}
