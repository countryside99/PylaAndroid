package com.pyla.ai.diagnostics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone

class DiagnosticExportTest {
    @get:Rule val folder = TemporaryFolder()

    private val snapshot = DiagnosticSnapshot("37427515-ff28-4680-b489-35d454ad6bf1", "# pyla-diagnostics schema=1\n1|0|E|app.start ä€\n")

    @Test fun fileNameCarriesTheLocalTimestamp() {
        // 2026-11-15 22:38:00 UTC
        val at = 1_794_782_280_000L
        assertEquals("pyla-diagnostics-20261115-223800.txt", DiagnosticExport.fileName(at, TimeZone.getTimeZone("UTC")))
        assertEquals("pyla-diagnostics-20261115-233800.txt", DiagnosticExport.fileName(at, TimeZone.getTimeZone("Europe/Berlin")))
    }

    @Test fun writesExactlyTheSnapshotBytes() {
        val out = ByteArrayOutputStream()
        DiagnosticExport.write(snapshot, out)
        assertArrayEquals(snapshot.text.toByteArray(Charsets.UTF_8), out.toByteArray())
        assertEquals(snapshot.byteCount, out.size())
    }

    @Test fun shareFileReplacesEarlierExportsAndLeavesOtherFilesAlone() {
        val dir = File(folder.root, "diagnostics")
        val first = DiagnosticExport.writeShareFile(dir, snapshot, 1_794_782_280_000L)
        val other = File(dir, "notes.txt").apply { writeText("keep") }
        val second = DiagnosticExport.writeShareFile(dir, snapshot, 1_794_782_340_000L)
        assertTrue(second.isFile)
        assertEquals(snapshot.text, second.readText(Charsets.UTF_8))
        assertTrue(first.name != second.name && !first.exists())
        assertTrue(other.isFile)
        assertEquals(2, dir.listFiles()!!.size)
    }
}
