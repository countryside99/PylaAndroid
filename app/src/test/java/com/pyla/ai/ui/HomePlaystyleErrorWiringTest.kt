package com.pyla.ai.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring only; the native fast-abort probe is the behavioral RED/GREEN authority. */
class HomePlaystyleErrorWiringTest {
    private fun source() = listOf(
        File("src/main/java/com/pyla/ai/ui/HomeScreen.kt"),
        File("app/src/main/java/com/pyla/ai/ui/HomeScreen.kt"),
    ).first { it.isFile }.readText()

    @Test fun lastErrorHasObservableStateAndTheTextRendersThatState() {
        val source = source()
        assertTrue(source.contains("var lastError by remember { mutableStateOf(BotStatus.lastError) }"))
        assertTrue(source.contains("if (lastError.isNotEmpty())"))
        assertTrue(source.contains("Text(lastError, color = PylaColors.Red"))
        assertFalse(source.contains("Text(BotStatus.lastError,"))
    }

    @Test fun existingPollRefreshesErrorWithoutRequiringObservedRunningTransition() {
        val source = source()
        val loop = source.substringAfter("LaunchedEffect(Unit) {").substringBefore("delay(400)")
        assertTrue(loop.contains("running = BotStatus.engineRunning\n            lastError = BotStatus.lastError"))
        assertFalse(loop.contains("if (running)"))
        assertEquals(1, Regex("lastError = BotStatus.lastError").findAll(loop).count())
    }
}
