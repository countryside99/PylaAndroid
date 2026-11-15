package com.pyla.ai.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class UiLayoutTest {
    @Test fun wideFramesKeepTheHeightOnlyMapping() {
        for ((w, h) in listOf(1136 to 640, 1422 to 640, 1920 to 1080, 2400 to 1080, 1280 to 540)) {
            assertEquals(h / 1080.0, UiLayout.unit(w, h), 0.0)
            assertEquals(h / 590.0, UiLayout.unit(w, h, 590.0), 0.0)
            for (y in listOf(0.0, 14.0, 500.0, 870.0, 1079.0)) {
                assertEquals(y * (h / 1080.0), UiLayout.y(y, w, h), 0.0)
                assertEquals(y * (h / 1080.0), UiLayout.top(y, 120.0, w, h), 0.0)
            }
        }
    }

    /** Positions measured on an 852x640 (4:3) frame against the same screens at 1136x640. */
    @Test fun narrowFrameMatchesMeasuredControls() {
        val w = 852; val h = 640; val ref = 640.0
        assertEquals(0.75, UiLayout.unit(w, h, ref), 0.002)
        assertEquals(10.0, UiLayout.y(14.0, w, h, ref), 1.0)    // "Rank:" heading, top
        assertEquals(79.0, UiLayout.y(105.0, w, h, ref), 1.0)   // result trophy icon, top
        assertEquals(99.0, UiLayout.y(131.0, w, h, ref), 1.0)   // "Defeated", top
        assertEquals(582.0, UiLayout.y(562.0, w, h, ref), 1.0)  // result EXIT / PLAY AGAIN, bottom
        assertEquals(586.0, UiLayout.y(567.0, w, h, ref), 1.0)  // defeated EXIT, bottom
        assertEquals(320.0, UiLayout.y(320.0, w, h, ref), 0.0)  // centre stays centred
    }
}
