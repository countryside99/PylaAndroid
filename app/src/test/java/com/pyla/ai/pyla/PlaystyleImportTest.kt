package com.pyla.ai.pyla

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * Focused JVM tests for the bounded, no-overwrite `.pyla` import boundary.
 *
 * Everything here exercises the real [PlaystyleImporter], real byte streams, real files and the
 * real [PyLexer]/[PyParser] compile check (via the production `validate` seam shape). Nothing is
 * mocked and no Android class is touched.
 */
class PlaystyleImportTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = tmp.newFolder("playstyles")
    }

    // ---------- helpers ----------

    /** Same compile step the production registry supplies: real lexer + real parser. */
    private fun compileLikeProduction(@Suppress("UNUSED_PARAMETER") name: String, content: String) {
        PyParser(PyLexer(content).tokenize()).parseModule()
    }

    private fun import(
        name: String,
        bytes: ByteArray,
        validate: (String, String) -> Unit = ::compileLikeProduction,
    ): PlaystyleImportResult =
        PlaystyleImporter.importFrom(ByteArrayInputStream(bytes), name, dir, validate)

    private fun importText(
        name: String,
        content: String,
        validate: (String, String) -> Unit = ::compileLikeProduction,
    ): PlaystyleImportResult = import(name, content.toByteArray(Charsets.UTF_8), validate)

    private fun successName(result: PlaystyleImportResult): String {
        assertTrue("expected Success but was $result", result is PlaystyleImportResult.Success)
        return (result as PlaystyleImportResult.Success).filename
    }

    private fun failure(result: PlaystyleImportResult): PlaystyleImportResult.Failure {
        assertTrue("expected Failure but was $result", result is PlaystyleImportResult.Failure)
        return result as PlaystyleImportResult.Failure
    }

    private fun storedNames(): List<String> =
        dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    private fun assertNothingStored() {
        assertEquals("no file (or temp) may remain", emptyList<String>(), storedNames())
    }

    private fun allFilesUnder(root: File): List<File> =
        root.walkTopDown().filter { it.isFile }.toList()

    private val validBody = "name = \"builder\"\nmovement = (1, 2)\n"

    // ---------- happy path ----------

    @Test
    fun validBuilderStyleFileIsStoredWithSanitizedLowercaseName() {
        val result = importText("my style.pyla", validBody)

        val name = successName(result)
        assertEquals("my_style.pyla", name)
        assertTrue(name.endsWith(".pyla"))
        assertEquals(validBody, File(dir, name).readText())
        // Readback through the real parser, as the registry will do on selection.
        compileLikeProduction(name, File(dir, name).readText())
        assertEquals(listOf("my_style.pyla"), storedNames())
    }

    @Test
    fun validateReceivesSanitizedCandidateNameAndNormalizedContent() {
        var seenName: String? = null
        var seenContent: String? = null

        val result = import(
            "  weird name!.PYLA ",
            "\uFEFFa = 1\r\nb = 2\rc = 3\r\n".toByteArray(Charsets.UTF_8),
        ) { n, c ->
            seenName = n
            seenContent = c
        }

        assertEquals("weird_name_.pyla", successName(result))
        assertEquals("weird_name_.pyla", seenName)
        assertEquals("a = 1\nb = 2\nc = 3\n", seenContent)
        assertEquals(3, seenContent!!.count { it == '\n' })
        assertFalse(seenContent!!.contains('\r'))
        assertFalse(seenContent!!.startsWith('\uFEFF'))
    }

    @Test
    fun utf8BomIsStrippedAndCrlfAndLoneCrBecomeLf() {
        val result = import(
            "lines.pyla",
            "\uFEFFx = 1\r\ny = 2\rz = 3\r\n".toByteArray(Charsets.UTF_8),
        )

        val bytes = File(dir, successName(result)).readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        assertEquals("x = 1\ny = 2\nz = 3\n", text)
        assertFalse(text.startsWith('\uFEFF'))
        assertFalse(bytes.any { it == '\r'.code.toByte() })
    }

    // ---------- rejected inputs ----------

    @Test
    fun invalidUtf8IsRejectedAsInvalidEncodingWithoutWriting() {
        var validateCalls = 0
        // 0xE9 is an invalid standalone UTF-8 continuation-free byte.
        val bytes = byteArrayOf(
            'm'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte(), 'e'.code.toByte(),
            0xE9.toByte(), ' '.code.toByte(), '='.code.toByte(), ' '.code.toByte(), '1'.code.toByte(),
        )

        val result = import("cafe.pyla", bytes) { _, _ -> validateCalls++ }

        val failure = failure(result)
        assertEquals(PlaystyleImportFailure.INVALID_ENCODING, failure.reason)
        assertNull(failure.line)
        assertEquals(0, validateCalls)
        assertNothingStored()
    }

    @Test
    fun truncatedMultibyteSequenceIsInvalidEncoding() {
        val failure = failure(import("truncated.pyla", byteArrayOf(0x6D, 0xC3.toByte())))
        assertEquals(PlaystyleImportFailure.INVALID_ENCODING, failure.reason)
        assertNothingStored()
    }

    @Test
    fun emptyAndBlankPayloadsAreRejectedAsEmpty() {
        assertEquals(PlaystyleImportFailure.EMPTY, failure(import("empty.pyla", ByteArray(0))).reason)
        assertEquals(
            PlaystyleImportFailure.EMPTY,
            failure(import("blank.pyla", " \n\t\r\n".toByteArray(Charsets.UTF_8))).reason,
        )
        assertEquals(
            PlaystyleImportFailure.EMPTY,
            failure(import("bomonly.pyla", "\uFEFF".toByteArray(Charsets.UTF_8))).reason,
        )
        assertNothingStored()
    }

    @Test
    fun sizeLimitIsExactly512KiB() {
        assertEquals(512 * 1024, PlaystyleImporter.MAX_BYTES)

        val maxSized = "x = 1\n" + "#".repeat(PlaystyleImporter.MAX_BYTES - 6)
        assertEquals(PlaystyleImporter.MAX_BYTES, maxSized.toByteArray(Charsets.UTF_8).size)
        val ok = importText("limit_ok.pyla", maxSized)
        assertTrue("exactly MAX_BYTES must import, got $ok", ok is PlaystyleImportResult.Success)
        assertEquals(maxSized.length.toLong(), File(dir, successName(ok)).length())

        val over = maxSized + "#"
        assertEquals(PlaystyleImporter.MAX_BYTES + 1, over.toByteArray(Charsets.UTF_8).size)
        val overResult = importText("limit_over.pyla", over)
        assertEquals(PlaystyleImportFailure.TOO_LARGE, failure(overResult).reason)
        assertEquals(listOf(successName(ok)), storedNames())
    }

    @Test
    fun oversizeStreamIsRejectedBeforeValidationAndNotStored() {
        var validateCalls = 0
        val bytes = ByteArray(PlaystyleImporter.MAX_BYTES + 1) { 'a'.code.toByte() }

        val result = import("huge.pyla", bytes) { _, _ -> validateCalls++ }

        assertEquals(PlaystyleImportFailure.TOO_LARGE, failure(result).reason)
        assertEquals(0, validateCalls)
        assertNothingStored()
    }

    @Test
    fun realParserRejectionSurfacesTheParserLineWithoutRawText() {
        val broken = "if True\n    x = 1\n"
        val expectedLine = try {
            compileLikeProduction("broken.pyla", broken)
            null
        } catch (e: PyParseException) {
            e.line
        }
        assertNotNull("fixture must actually fail the parser", expectedLine)

        val result = importText("broken.pyla", broken)

        val failure = failure(result)
        assertEquals(PlaystyleImportFailure.INVALID_SCRIPT, failure.reason)
        assertEquals(expectedLine, failure.line)
        assertTrue("line must be a real source line", (failure.line ?: 0) >= 1)
        val rendered = result.toString()
        assertFalse("no parser text may leak: $rendered", rendered.contains("expected", ignoreCase = true))
        assertFalse(rendered.contains("Line ", ignoreCase = true))
        assertNothingStored()
    }

    @Test
    fun validatorExceptionTextIsNeverExposed() {
        val result = importText("boom.pyla", validBody) { _, _ ->
            throw IllegalStateException("SECRET marker 42")
        }

        val failure = failure(result)
        assertEquals(PlaystyleImportFailure.INVALID_SCRIPT, failure.reason)
        assertNull(failure.line)
        assertFalse(failure.toString().contains("SECRET"))
        assertNothingStored()
    }

    // ---------- stream and IO failures ----------

    @Test
    fun nullInputIsReadFailed() {
        val result = PlaystyleImporter.importFrom(null, "missing.pyla", dir, ::compileLikeProduction)
        assertEquals(PlaystyleImportFailure.READ_FAILED, failure(result).reason)
        assertNothingStored()
    }

    @Test
    fun streamReadFailureIsReadFailed() {
        val stream = object : InputStream() {
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("read failed")
        }

        val result = PlaystyleImporter.importFrom(stream, "io.pyla", dir, ::compileLikeProduction)

        assertEquals(PlaystyleImportFailure.READ_FAILED, failure(result).reason)
        assertNothingStored()
    }

    @Test
    fun closeFailureIsReadFailedAndWritesNothing() {
        val stream = object : InputStream() {
            private val payload = validBody.toByteArray(Charsets.UTF_8)
            private var pos = 0
            override fun read(): Int = if (pos < payload.size) payload[pos++].toInt() and 0xFF else -1
            override fun close() = throw IOException("close failed")
        }

        val result = PlaystyleImporter.importFrom(stream, "close.pyla", dir, ::compileLikeProduction)

        assertEquals(PlaystyleImportFailure.READ_FAILED, failure(result).reason)
        assertNothingStored()
    }

    @Test
    fun inputStreamIsClosedOnSuccessAndOnValidationFailure() {
        class TrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() {
                closed = true
                super.close()
            }
        }

        val ok = TrackingStream(validBody.toByteArray(Charsets.UTF_8))
        assertTrue(PlaystyleImporter.importFrom(ok, "ok.pyla", dir, ::compileLikeProduction) is PlaystyleImportResult.Success)
        assertTrue("stream must be closed on success", ok.closed)

        val rejected = TrackingStream("if True\n".toByteArray(Charsets.UTF_8))
        assertEquals(
            PlaystyleImportFailure.INVALID_SCRIPT,
            failure(PlaystyleImporter.importFrom(rejected, "bad.pyla", dir, ::compileLikeProduction)).reason,
        )
        assertTrue("stream must be closed on validation failure", rejected.closed)
    }

    @Test
    fun interruptionDuringValidationPropagatesAndLeavesNoFile() {
        assertThrows(InterruptedException::class.java) {
            importText("cancel.pyla", validBody) { _, _ -> throw InterruptedException("cancelled") }
        }
        assertNothingStored()
    }

    @Test
    fun unwritableDirectoryIsWriteFailed() {
        val notADir = File(tmp.root, "not_a_dir")
        notADir.writeText("x")
        assertTrue(notADir.isFile)

        val result = PlaystyleImporter.importFrom(
            ByteArrayInputStream(validBody.toByteArray(Charsets.UTF_8)),
            "x.pyla",
            notADir,
            ::compileLikeProduction,
        )

        assertEquals(PlaystyleImportFailure.WRITE_FAILED, failure(result).reason)
        assertEquals("x", notADir.readText())
    }

    @Test
    fun directoryVanishingBeforeStagingIsWriteFailedWithoutLeaks() {
        val result = importText("gone.pyla", validBody) { _, _ -> dir.deleteRecursively() }

        assertEquals(PlaystyleImportFailure.WRITE_FAILED, failure(result).reason)
        assertFalse(dir.exists())
    }

    @Test
    fun symlinkedDirectoryIsRejected() {
        val realDir = tmp.newFolder("real_playstyles")
        val link = File(tmp.root, "linked_playstyles")
        val created = try {
            Files.createSymbolicLink(link.toPath(), realDir.toPath())
            true
        } catch (_: Exception) {
            false
        }
        assumeTrue("filesystem cannot create symlinks", created)

        val result = PlaystyleImporter.importFrom(
            ByteArrayInputStream(validBody.toByteArray(Charsets.UTF_8)),
            "link.pyla",
            link,
            ::compileLikeProduction,
        )

        assertEquals(PlaystyleImportFailure.WRITE_FAILED, failure(result).reason)
        assertEquals(emptyList<File>(), allFilesUnder(realDir))
    }

    // ---------- names, collisions, preservation ----------

    @Test
    fun existingFileIsNeverOverwrittenAndOriginalBytesSurvive() {
        val existing = File(dir, "shared.pyla")
        existing.writeText("movement = (7, 7)\n")
        val before = existing.readBytes()

        val result = importText("shared.pyla", "movement = (1, 2)\n")

        val name = successName(result)
        assertEquals("shared_1.pyla", name)
        assertTrue("original bytes changed", before.contentEquals(existing.readBytes()))
        assertEquals("movement = (1, 2)\n", File(dir, name).readText())
        assertEquals(listOf("shared.pyla", "shared_1.pyla"), storedNames())
    }

    @Test
    fun repeatedImportsGetDistinctSuffixesAndKeepEveryFile() {
        val names = (1..3).map { successName(importText("cycle.pyla", "movement = ($it, $it)\n")) }

        assertEquals(listOf("cycle.pyla", "cycle_1.pyla", "cycle_2.pyla"), names)
        assertEquals(3, names.map { File(dir, it).readText() }.distinct().size)
    }

    @Test
    fun caseInsensitiveNameCollisionKeepsBothFiles() {
        File(dir, "My.pyla").writeText("movement = (5, 5)\n")
        val before = File(dir, "My.pyla").readBytes()

        val name = successName(importText("my.PYLA", "movement = (1, 2)\n"))

        assertEquals("my_1.pyla", name)
        assertTrue(before.contentEquals(File(dir, "My.pyla").readBytes()))
        assertEquals(2, storedNames().size)
        assertEquals(2, storedNames().count { it.endsWith(".pyla") })
    }

    @Test
    fun uppercaseExtensionIsNormalizedToLowercase() {
        val name = successName(importText("SHOUT.PYLA", validBody))
        assertEquals("SHOUT.pyla", name)
        assertEquals(listOf("SHOUT.pyla"), storedNames())
    }

    @Test
    fun pathTraversalNamesCannotEscapeTheDirectory() {
        val name = successName(importText("../../escape.pyla", validBody))
        assertEquals("escape.pyla", name)
        assertEquals(dir.canonicalFile, File(dir, name).canonicalFile.parentFile)
        val outside = File(dir.parentFile, "escape.pyla")
        assertFalse("must not write outside target directory", outside.exists())

        val win = successName(importText("..\\..\\escape2.pyla", validBody))
        assertEquals("escape2.pyla", win)
        assertFalse(File(dir.parentFile, "escape2.pyla").exists())
    }

    @Test
    fun unsafeCharactersAndOverlongNamesAreSanitizedAndBounded() {
        val odd = successName(importText("bad:name*?.pyla", validBody))
        assertTrue("unexpected filename: $odd", Regex("^[A-Za-z0-9._-]+\\.pyla$").matches(odd))
        assertFalse(odd.contains('/'))
        assertFalse(odd.contains('\\'))

        val long = successName(importText("a".repeat(400) + ".pyla", validBody))
        assertTrue("name length ${long.length} must stay bounded", long.length <= 80)
        assertTrue(long.endsWith(".pyla"))
        assertTrue(long.startsWith("a"))
        assertTrue(storedNames().all { it == odd || it == long })
    }

    @Test
    fun dotOnlyNamesFallBackToAStableSafeName() {
        val name = successName(importText("..", validBody))
        assertEquals("import.pyla", name)
        assertEquals(dir.canonicalFile, File(dir, name).canonicalFile.parentFile)
    }

    @Test
    fun noTemporaryFilesRemainAfterSuccessOrFailure() {
        successName(importText("done.pyla", validBody))
        failure(importText("broken.pyla", "if True\n"))

        val leftovers = storedNames().filter { it.startsWith(".") || it.endsWith(".tmp") }
        assertEquals(emptyList<String>(), leftovers)
    }
}
