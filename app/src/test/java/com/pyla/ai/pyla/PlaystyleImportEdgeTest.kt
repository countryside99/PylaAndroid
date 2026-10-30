package com.pyla.ai.pyla

import java.io.InputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaystyleImportEdgeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val body = "movement = (0, 0)\n"

    @Test fun actualDeepParserNestingIsRejectedWithoutPublishingAFile() {
        val directory = temporary.newFolder()
        val nested = "movement = " + "(".repeat(12000) + "0" + ")".repeat(12000) + "\n"
        val result = PlaystyleImporter.importFrom(nested.byteInputStream(), "nested.pyla", directory) { _, text ->
            PyParser(PyLexer(text).tokenize()).parseModule()
        }
        assertTrue(result is PlaystyleImportResult.Failure)
        assertEquals(PlaystyleImportFailure.INVALID_SCRIPT, (result as PlaystyleImportResult.Failure).reason)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun oversizedStreamConsumesOnlyTheLimitPlusOneByte() {
        val directory = temporary.newFolder()
        var consumed = 0
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int { consumed++; return 32 }
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                consumed += len
                java.util.Arrays.fill(bytes, off, off + len, 32.toByte())
                return len
            }
            override fun close() { closed = true }
        }
        val result = PlaystyleImporter.importFrom(input, "large.pyla", directory) { _, _ -> fail("must not parse oversized input") }
        assertEquals(PlaystyleImportResult.Failure(PlaystyleImportFailure.TOO_LARGE), result)
        assertEquals(PlaystyleImporter.MAX_BYTES + 1, consumed)
        assertTrue(closed)
    }

    @Test fun cancelledDocumentReadClosesAndPropagatesCancellation() {
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = throw CancellationException("fixture cancellation")
            override fun close() { closed = true }
        }
        try {
            PlaystyleImporter.importFrom(input, "cancel.pyla", temporary.newFolder()) { _, _ -> }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(closed)
        }
    }

    @Test fun parentStorageAliasImportsThroughRealParserLikeAndroidNamespace() {
        val actual = temporary.newFolder("actual")
        val directory = java.io.File(actual, "playstyles").apply { mkdir() }
        val alias = java.io.File(temporary.root, "app-storage-alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), actual.toPath())
        val result = PlaystyleImporter.importFrom(body.byteInputStream(), "native.pyla", java.io.File(alias, "playstyles")) { _, text ->
            PyParser(PyLexer(text).tokenize()).parseModule()
        }
        assertEquals(PlaystyleImportResult.Success("native.pyla"), result)
        assertEquals(body, java.io.File(directory, "native.pyla").readText())
    }

    @Test fun destinationLeafSymlinkStillFailsWithoutPublishingAnything() {
        val actual = temporary.newFolder("actual")
        val link = java.io.File(temporary.root, "playstyles")
        java.nio.file.Files.createSymbolicLink(link.toPath(), actual.toPath())
        val result = PlaystyleImporter.importFrom(body.byteInputStream(), "native.pyla", link) { _, text ->
            PyParser(PyLexer(text).tokenize()).parseModule()
        }
        assertEquals(PlaystyleImportResult.Failure(PlaystyleImportFailure.WRITE_FAILED), result)
        assertTrue(actual.listFiles()!!.isEmpty())
    }

    @Test fun maximumLengthNameStillAllowsAnIndependentCollisionCopy() {
        val directory = temporary.newFolder()
        val name = "x".repeat(75) + ".pyla"
        val original = java.io.File(directory, name).apply { writeText("preserved") }
        val result = PlaystyleImporter.importFrom(body.byteInputStream(), name, directory) { _, text ->
            PyParser(PyLexer(text).tokenize()).parseModule()
        } as PlaystyleImportResult.Success
        assertNotEquals(name, result.filename)
        assertTrue(result.filename.length <= PlaystyleImporter.MAX_FILENAME_LENGTH)
        assertEquals("preserved", original.readText())
        assertEquals(body, java.io.File(directory, result.filename).readText())
    }
}
