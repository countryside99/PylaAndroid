package com.pyla.ai.diagnostics

import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Local export of a diagnostic snapshot. The app has no network access: a report only leaves
 * the device as a file the user saved or shared themselves.
 *
 * Plain Kotlin so the naming and the bytes written are unit-testable without Android.
 */
object DiagnosticExport {
    const val MIME_TYPE = "text/plain"
    private const val PREFIX = "pyla-diagnostics-"
    private const val SUFFIX = ".txt"

    /** `pyla-diagnostics-20261115-233800.txt` in the device's local time. */
    fun fileName(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply { timeZone = zone }
        return PREFIX + format.format(Date(nowMs)) + SUFFIX
    }

    /** Writes exactly the snapshot's UTF-8 bytes. The caller owns and closes [out]. */
    fun write(snapshot: DiagnosticSnapshot, out: OutputStream) {
        out.write(snapshot.text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /**
     * Writes [snapshot] into [directory] for sharing and removes earlier exports there, so at most
     * one report is kept in the app cache.
     */
    fun writeShareFile(directory: File, snapshot: DiagnosticSnapshot, nowMs: Long): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IllegalStateException("export directory unavailable")
        directory.listFiles()?.forEach { old ->
            if (old.isFile && old.name.startsWith(PREFIX) && old.name.endsWith(SUFFIX)) old.delete()
        }
        val target = File(directory, fileName(nowMs))
        target.outputStream().use { write(snapshot, it) }
        return target
    }
}
