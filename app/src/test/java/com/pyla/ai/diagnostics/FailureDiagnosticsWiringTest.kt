package com.pyla.ai.diagnostics

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-wiring checks for the observation-only workflow failure seams in the owned files.
 *
 * These assert what the production source contains; they are not executable runtime evidence and
 * cannot prove device behaviour, game effects or OCR accuracy.
 */
class FailureDiagnosticsWiringTest {

    private fun src(rel: String): String =
        listOf(File("src/main/java/com/pyla/ai/$rel"), File("app/src/main/java/com/pyla/ai/$rel"))
            .first { it.isFile }.readText()

    private fun region(body: String, start: String, end: String): String {
        val from = body.indexOf(start)
        assertTrue("missing region start: $start", from >= 0)
        val to = body.indexOf(end, from + start.length)
        return if (to < 0) body.substring(from) else body.substring(from, to)
    }

    private val withoutPayloads = Regex("""DiagnosticLog\.(failure|terminal)\([^)\n]*(\$\{|\.message|PylaConfig|File\(|http)""")

    @Test
    fun `capture start observes failures without changing semantics`() {
        val c = src("capture/CaptureService.kt")
        val start = region(c, "ACTION_START ->", "ACTION_STOP ->")
        assertTrue(start.contains("DiagnosticLog.failure(\"capture\", \"start\", \"start_threw\", t)"))
        assertTrue("the original throw must be preserved", start.contains("throw t"))
        assertTrue("missing result data must not silently skip capture", start.contains("result_data_missing"))
        assertTrue(c.contains("DiagnosticLog.failure(\"capture\", \"frame_acquire\", \"acquire_threw\", t)"))
        assertTrue(c.contains("DiagnosticLog.failure(\"capture\", \"stop\", \"projection_release_threw\", t)"))
        assertTrue("projection-stop callback is observed without inventing its initiating caller",
            c.contains("DiagnosticLog.failure(\"capture\", \"projection\", \"projection_stopped_callback\")"))
        assertTrue("capture start still records its success event", c.contains("DiagnosticLog.event(\"capture.started\""))
    }

    @Test
    fun `input dispatch and authority failures carry fixed causes`() {
        val i = src("input/InputService.kt")
        assertTrue(i.contains("DiagnosticLog.failure(\"input\", op, \"authority_guard_threw\", t)"))
        assertTrue(i.contains("DiagnosticLog.failure(\"input\", \"gesture_build\", \"build_threw\", t)"))
        assertTrue(i.contains("DiagnosticLog.failure(\"input\", \"gesture_dispatch\", \"dispatch_threw\", t)"))
        val allows = region(i, "fun allows(", "private class Channel")
        assertTrue("a throwing guard is still a denial", allows.contains("true"))
        assertTrue("denial must still record the why", allows.contains("DiagnosticLog.event(\"input.gesture.denied\""))
        assertTrue("dispatch rejection stays a non-folded event",
            i.contains("DiagnosticLog.event(\"input.gesture.rejected\""))
    }

    @Test
    fun `stage failures name the failing step and keep throw and interrupt order`() {
        val s = src("engine/StageManager.kt")
        val await = region(s, "fun await(", "private fun beginNavigation")
        val interrupt = await.indexOf("Thread.currentThread().interrupt()")
        val record = await.indexOf("DiagnosticLog.failure(\"stage\", \"await\", \"interrupted\", t)")
        assertTrue("interrupt flag must be restored", interrupt >= 0)
        assertTrue("interrupt must be restored before the observation", record > interrupt)
        assertTrue(await.contains("return false"))
        assertTrue("the selection exception must still be thrown unchanged",
            s.contains("throw BrawlerSelectionFailedException(result)"))
        assertTrue(s.contains("DiagnosticLog.failure(\"stage\", \"brawler_select\", \"selection_not_verified\")"))
        assertTrue(s.contains("DiagnosticLog.failure(\"stage\", \"relaunch\", \"authority_refused\")"))
        assertTrue(s.contains("DiagnosticLog.failure(\"stage\", \"popup_close\", \"template_missing\")"))
    }

    @Test
    fun `engine stop is attributable and loop failures are categorised`() {
        val e = src("engine/BotEngine.kt")
        assertTrue(e.contains("fun stop(cause: String)"))
        assertTrue(e.contains("DiagnosticLog.terminal(\"engine\", \"stop\", \"requested\", cause)"))
        (listOf(
            "capture_unavailable", "selected_playstyle_unavailable", "bootstrap_failure", "run_for_minutes",
            "push_targets_reached", "brawler_selection_unverified", "empty_queue",
        )).forEach { assertTrue("missing attributed stop: $it", e.contains("stop(\"$it\")")) }
        assertTrue(e.contains("DiagnosticLog.failure(\"engine\", \"main_loop\", \"unhandled\", t)"))
        assertTrue(e.contains("DiagnosticLog.failure(\"engine\", \"stage_dispatch\", \"threw\", t)"))
        assertTrue("hot-path config denial must be rate-limited",
            e.contains("DiagnosticLog.sample(\"engine.setting_denied\", \"setting=game_package\", 60_000)"))
    }

    @Test
    fun `no failure or terminal record interpolates payloads`() {
        listOf(
            "capture/CaptureService.kt", "input/InputService.kt",
            "engine/StageManager.kt", "engine/BotEngine.kt",
        ).forEach {
            assertFalse("$it must not put config paths or throwable messages into failure/terminal",
                withoutPayloads.containsMatchIn(src(it)))
        }
    }
}
