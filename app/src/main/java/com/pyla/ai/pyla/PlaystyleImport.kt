package com.pyla.ai.pyla

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Terminal outcome of one `.pyla` import. Fixed set — safe to log or show in the UI.
 *
 * [Failure.line] is a validated 1-based source line from the real parser when one is known;
 * [Failure.reason] never carries raw exception text, file paths or script content.
 */
sealed class PlaystyleImportResult {
    data class Success(val filename: String) : PlaystyleImportResult()
    data class Failure(
        val reason: PlaystyleImportFailure,
        val line: Int? = null,
    ) : PlaystyleImportResult()
}

/** Coarse, user-safe reasons an import can be rejected. */
enum class PlaystyleImportFailure {
    EMPTY,
    TOO_LARGE,
    INVALID_ENCODING,
    INVALID_SCRIPT,
    READ_FAILED,
    WRITE_FAILED,
}

/**
 * Bounded, atomic `.pyla` document import.
 *
 * Guarantees:
 * - reads at most [MAX_BYTES] from a stream it owns and always closes;
 * - decodes strict UTF-8, strips one leading BOM and normalizes CRLF/CR to LF;
 * - rejects empty payloads, invalid encodings and compile failures with a fixed reason;
 * - never overwrites an existing (user or bundled) file — collisions get a `_N` suffix;
 * - stages into a temp file in the target directory and commits with a rename, so a failure
 *   leaves no new final file and existing bytes untouched.
 *
 * Pure JVM: no Android types, so the whole boundary is unit-testable.
 */
object PlaystyleImporter {

    /** Hard input bound; anything larger is rejected before decoding or compiling. */
    const val MAX_BYTES: Int = 512 * 1024

    /** Upper bound for the final filename, extension included. */
    const val MAX_FILENAME_LENGTH: Int = 80

    private const val EXTENSION = ".pyla"
    private const val FALLBACK_STEM = "import"
    private const val MAX_STEM_LENGTH = MAX_FILENAME_LENGTH - 5
    private const val MAX_COLLISION_ATTEMPTS = 999
    private const val READ_CHUNK_BYTES = 8 * 1024
    private const val MAX_EMPTY_READS = 64
    private const val TEMP_PREFIX = ".pyla-import-"
    private const val TEMP_SUFFIX = ".tmp"

    /** Same character policy as the legacy registry sanitizer. */
    private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9._-]")

    /** Serializes name selection + commit inside the process, so two imports cannot pick the same name. */
    private val FILE_LOCK = Any()

    /**
     * Imports one `.pyla` document.
     *
     * @param input document stream; owned and closed by this call. `null` is [PlaystyleImportFailure.READ_FAILED].
     * @param originalName display name from the document provider; only its basename is used.
     * @param directory destination directory (normally the extracted `playstyles` dir).
     * @param validate production compile check, called with the sanitized candidate filename and the
     *   normalized content. It may throw [PyParseException] to report a source line.
     */
    fun importFrom(
        input: InputStream?,
        originalName: String,
        directory: File,
        validate: (String, String) -> Unit,
    ): PlaystyleImportResult {
        if (input == null) return PlaystyleImportResult.Failure(PlaystyleImportFailure.READ_FAILED)

        val bytes = when (val outcome = readBounded(input)) {
            is ReadOutcome.Bytes -> outcome.value
            ReadOutcome.TooLarge -> return PlaystyleImportResult.Failure(PlaystyleImportFailure.TOO_LARGE)
            ReadOutcome.Failed -> return PlaystyleImportResult.Failure(PlaystyleImportFailure.READ_FAILED)
        }

        val decoded = try {
            decodeStrictUtf8(bytes)
        } catch (_: CharacterCodingException) {
            return PlaystyleImportResult.Failure(PlaystyleImportFailure.INVALID_ENCODING)
        }

        val content = normalize(decoded)
        if (content.isEmpty() || content.isBlank()) {
            return PlaystyleImportResult.Failure(PlaystyleImportFailure.EMPTY)
        }

        val candidateName = sanitizeFilename(originalName)
        try {
            validate(candidateName, content)
        } catch (_: StackOverflowError) {
            // A bounded byte payload can still contain excessive parser nesting.
            return PlaystyleImportResult.Failure(PlaystyleImportFailure.INVALID_SCRIPT)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: PyParseException) {
            return PlaystyleImportResult.Failure(
                PlaystyleImportFailure.INVALID_SCRIPT,
                e.line.takeIf { it > 0 },
            )
        } catch (_: Exception) {
            return PlaystyleImportResult.Failure(PlaystyleImportFailure.INVALID_SCRIPT)
        }

        return try {
            PlaystyleImportResult.Success(
                stageAndCommit(directory, candidateName, content.toByteArray(StandardCharsets.UTF_8)),
            )
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {
            PlaystyleImportResult.Failure(PlaystyleImportFailure.WRITE_FAILED)
        }
    }

    // ---------- bounded read ----------

    private sealed class ReadOutcome {
        class Bytes(val value: ByteArray) : ReadOutcome()
        object TooLarge : ReadOutcome()
        object Failed : ReadOutcome()
    }

    /** Reads at most [MAX_BYTES] + 1 bytes; owns and closes [input] on every path. */
    private fun readBounded(input: InputStream): ReadOutcome {
        val chunk = ByteArray(READ_CHUNK_BYTES)
        val collected = ByteArrayOutputStream()
        var total = 0
        var emptyReads = 0
        try {
            while (true) {
                val read = input.read(chunk, 0, minOf(chunk.size, MAX_BYTES + 1 - total))
                if (read < 0) break
                if (read == 0) {
                    if (++emptyReads > MAX_EMPTY_READS) {
                        closeQuietly(input)
                        return ReadOutcome.Failed
                    }
                    continue
                }
                emptyReads = 0
                if (read > MAX_BYTES - total) {
                    closeQuietly(input)
                    return ReadOutcome.TooLarge
                }
                collected.write(chunk, 0, read)
                total += read
            }
        } catch (e: java.util.concurrent.CancellationException) {
            closeQuietly(input)
            throw e
        } catch (e: InterruptedException) {
            closeQuietly(input)
            throw e
        } catch (_: Exception) {
            closeQuietly(input)
            return ReadOutcome.Failed
        }

        val closed = try {
            input.close()
            true
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {
            false
        }
        return if (closed) ReadOutcome.Bytes(collected.toByteArray()) else ReadOutcome.Failed
    }

    private fun closeQuietly(input: InputStream) {
        try {
            input.close()
        } catch (_: Exception) {
            // best effort: the caller already reports a read/encoding failure
        }
    }

    // ---------- decode + normalize ----------

    private fun decodeStrictUtf8(bytes: ByteArray): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    /** Strips one leading BOM and converts CRLF/CR to LF. */
    private fun normalize(text: String): String {
        val withoutBom = if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text
        return withoutBom.replace("\r\n", "\n").replace('\r', '\n')
    }

    // ---------- filename policy ----------

    private fun sanitizeFilename(originalName: String): String {
        val basename = originalName.substringAfterLast('/').substringAfterLast('\\').trim()
        val withoutExtension = if (basename.lowercase(Locale.ROOT).endsWith(EXTENSION)) {
            basename.dropLast(EXTENSION.length)
        } else {
            basename
        }
        val cleaned = withoutExtension.replace(UNSAFE_FILENAME_CHARS, "_").take(MAX_STEM_LENGTH)
        val stem = if (cleaned.isEmpty() || cleaned.all { it == '.' }) FALLBACK_STEM else cleaned
        return stem + EXTENSION
    }

    // ---------- atomic staging ----------

    private class ImportWriteFailure : Exception()

    private fun stageAndCommit(directory: File, baseName: String, bytes: ByteArray): String =
        synchronized(FILE_LOCK) {
            val canonicalDir = try {
                directory.canonicalFile
            } catch (_: Exception) {
                throw ImportWriteFailure()
            }
            // Deny a symlink leaf, but allow Android's legitimate app-storage parent
            // aliases (/data/user/0 -> /data/data). All containment uses the real dir.
            if (java.nio.file.Files.isSymbolicLink(directory.toPath())) throw ImportWriteFailure()

            val target = chooseUniqueTarget(canonicalDir, baseName)
            if (target.canonicalFile.parentFile != canonicalDir) throw ImportWriteFailure()

            var staged: File? = null
            try {
                staged = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, canonicalDir)
                FileOutputStream(staged).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                // Re-check under the lock: never replace a file that appeared meanwhile.
                if (existsIgnoringCase(canonicalDir, target.name)) throw ImportWriteFailure()
                if (!staged.renameTo(target)) throw ImportWriteFailure()
                staged = null
                target.name
            } finally {
                staged?.let { leftover -> runCatching { leftover.delete() } }
            }
        }

    private fun chooseUniqueTarget(directory: File, baseName: String): File {
        val stem = baseName.dropLast(EXTENSION.length)
        var attempt = 0
        while (attempt <= MAX_COLLISION_ATTEMPTS) {
            val suffix = "_$attempt$EXTENSION"
            val name = if (attempt == 0) baseName else stem.take(MAX_FILENAME_LENGTH - suffix.length) + suffix
            if (name.length <= MAX_FILENAME_LENGTH && !existsIgnoringCase(directory, name)) {
                return File(directory, name)
            }
            attempt++
        }
        throw ImportWriteFailure()
    }

    /** Case-folded existence check so a case-insensitive volume cannot hide a collision. */
    private fun existsIgnoringCase(directory: File, name: String): Boolean {
        if (File(directory, name).exists()) return true
        val entries = directory.list() ?: throw ImportWriteFailure()
        return entries.any { it.equals(name, ignoreCase = true) }
    }
}
