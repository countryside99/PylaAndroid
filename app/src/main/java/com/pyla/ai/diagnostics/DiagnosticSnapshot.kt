package com.pyla.ai.diagnostics

import java.security.MessageDigest

/**
 * Immutable view of one diagnostics snapshot.
 *
 * The text is already sanitized by [DiagnosticLog] before it ever reaches this class, so an
 * instance can be handed to the export layer without re-reading the ring buffer. [sha256] and
 * [byteCount] describe the exact UTF-8 bytes of [text] so a reader can verify what was
 * exported. Both are computed once and never change.
 */
class DiagnosticSnapshot(
    val reportId: String,
    val text: String,
) {
    /** UTF-8 byte length of [text]; this is what the transport must send. */
    val byteCount: Int = utf8Size(text)

    /** Lowercase hex SHA-256 over the UTF-8 bytes of [text]. */
    val sha256: String = sha256Hex(text)

    override fun toString(): String = "DiagnosticSnapshot(reportId=$reportId, byteCount=$byteCount, sha256=$sha256)"

    companion object {
        /** Never throws: a hashing failure yields an empty digest rather than taking the app down. */
        private fun sha256Hex(text: String): String = try {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            val out = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                out.append(HEX[v ushr 4])
                out.append(HEX[v and 0x0F])
            }
            out.toString()
        } catch (_: Throwable) {
            ""
        }

        private fun utf8Size(text: String): Int = try {
            text.toByteArray(Charsets.UTF_8).size
        } catch (_: Throwable) {
            0
        }

        private const val HEX = "0123456789abcdef"
    }
}