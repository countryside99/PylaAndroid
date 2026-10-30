package com.pyla.ai.pyla

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Export compatibility gate for `tools/playstyle-builder/index.html`.
 *
 * The checked-in `example-export.pyla` is a real browser download from the builder. This test keeps
 * the generated document loadable by the Android import path: the metadata header must stay a
 * single JSON line and the body must compile with the production lexer/parser. It deliberately does
 * not touch org.json or Android classes, so it runs as a plain JVM test in CI.
 *
 * What this does not prove: the script's gameplay behaviour, native engine callbacks, on-device
 * import or a live match. Those stay outside this gate.
 */
class PlaystyleBuilderExportTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    /** Resolves the committed example from the Gradle module directory (`app/`) or repo root. */
    private fun exampleFile(): File {
        val relative = "tools/playstyle-builder/example-export.pyla"
        val workingDir = requireNotNull(System.getProperty("user.dir")) { "user.dir unavailable" }
        val candidates = listOf(File("..", relative), File(relative)) +
            generateSequence(File(workingDir).absoluteFile) { it.parentFile }
                .take(4)
                .map { File(it, relative) }
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("missing builder example at $relative (cwd=$workingDir)")
    }

    /** Mirrors `PylaScript.parseContent` for the byte-level import check: strip header, compile body. */
    private fun compileLikeImporter(@Suppress("UNUSED_PARAMETER") name: String, content: String) {
        val lines = content.split("\n")
        val headerIndex = lines.indexOfFirst { it.isNotBlank() }
        assertTrue("metadata header line must exist", headerIndex >= 0 && lines[headerIndex].trimStart().startsWith("{"))
        val body = lines.drop(headerIndex + 1).joinToString("\n")
        PyParser(PyLexer(body).tokenize()).parseModule()
    }

    @Test
    fun exampleExportKeepsSingleLineMetadataHeader() {
        val text = exampleFile().readText()
        val lines = text.split("\n")
        val header = lines.first { it.isNotBlank() }
        assertTrue("header must start with {", header.startsWith("{"))
        assertTrue("header must end with }", header.endsWith("}"))
        assertEquals("metadata must stay on one line", 1, lines.count { it.trimStart().startsWith("{") })
        assertTrue("header must name the playstyle", header.contains("\"name\":\"My Android Playstyle\""))
        assertTrue("export must not contain CR", !text.contains("\r"))
        assertTrue("export must not contain NUL", !text.contains('\u0000'))
        assertTrue("body must exist", lines.drop(1).joinToString("\n").isNotBlank())
    }

    @Test
    fun exampleExportCompilesWithProductionParser() {
        compileLikeImporter("example-export.pyla", exampleFile().readText())
    }

    @Test
    fun exampleExportImportsThroughProductionImporter() {
        val dir = tmp.newFolder("playstyles")
        val bytes = exampleFile().readBytes()
        val result = PlaystyleImporter.importFrom(
            ByteArrayInputStream(bytes),
            "example-export.pyla",
            dir,
            ::compileLikeImporter,
        )
        assertTrue("expected Success but was $result", result is PlaystyleImportResult.Success)
        assertEquals("example-export.pyla", (result as PlaystyleImportResult.Success).filename)
        val stored = File(dir, result.filename)
        assertTrue("imported file must exist", stored.isFile)
        assertEquals("import must not alter the export", bytes.toList(), stored.readBytes().toList())
    }
}
