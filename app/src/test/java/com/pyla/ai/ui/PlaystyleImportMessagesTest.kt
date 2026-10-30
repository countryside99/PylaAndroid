package com.pyla.ai.ui

import com.pyla.ai.pyla.PlaystyleImportFailure
import com.pyla.ai.pyla.PlaystyleImportResult
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PlaystyleImportMessagesTest {
    @Test fun everyFailureHasVisibleFixedEnglishText() {
        for (reason in PlaystyleImportFailure.values()) {
            val text = playstyleImportFailureText(PlaystyleImportResult.Failure(reason))
            assertTrue(reason.name, text.startsWith("Import failed:"))
            assertTrue(text.length > 25)
        }
    }

    @Test fun parserLineIsBoundedAndOnlyUsedForSyntaxErrors() {
        val syntax = PlaystyleImportResult.Failure(PlaystyleImportFailure.INVALID_SCRIPT, 17)
        assertTrue(playstyleImportFailureText(syntax).contains("line 17"))
        for (line in listOf(-1, 0, 1_000_001)) {
            assertFalse(playstyleImportFailureText(syntax.copy(line = line)).contains("near line"))
        }
        assertFalse(playstyleImportFailureText(syntax.copy(reason = PlaystyleImportFailure.READ_FAILED)).contains("line 17"))
    }

    private fun source(relative: String): String =
        listOf(File("src/main/java/com/pyla/ai/$relative"), File("app/src/main/java/com/pyla/ai/$relative"))
            .first { it.isFile }.readText()

    /** Wiring assertions supplement native document-picker verification; not a Compose runtime test. */
    @Test fun documentCallbackUsesStructuredBoundedImporterOffUiThread() {
        val screen = source("ui/PlaystylesScreen.kt")
        assertTrue(screen.contains("withContext(Dispatchers.IO)"))
        assertTrue(screen.contains("PlaystyleRegistry.importDocument(name, context.contentResolver.openInputStream(uri))"))
        assertTrue(screen.contains("playstyleImportFailureText(result)"))
        assertTrue(screen.contains("importMessage?.let"))
        assertTrue(screen.contains("!running && !importing"))
        assertFalse(screen.contains("bufferedReader()?.use { it.readText() }"))
        assertFalse(screen.contains("catch (_: Exception) {}"))
    }

    @Test fun engineDoesNotReplaceACompileFailedSelectionWithAnotherFile() {
        val engine = source("engine/BotEngine.kt")
        val loading = engine.substringAfter("val selectedPlaystyle = PlaystyleSelection.effectivePlaystyle()")
            .substringBefore("applyConfiguredPlayOrder()")
        assertTrue(loading.contains("val pylaScript = PlaystyleRegistry.load(selectedPlaystyle)"))
        assertTrue(loading.contains("stop(\"selected_playstyle_unavailable\")"))
        assertTrue(loading.contains("Selected playstyle could not be loaded"))
        assertFalse(loading.contains("PlaystyleRegistry.DEFAULT_PLAYSTYLE"))
        assertFalse(loading.contains("PlaystyleRegistry.listMeta()"))
    }
}
