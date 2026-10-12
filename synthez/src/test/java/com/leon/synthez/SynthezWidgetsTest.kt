package com.leon.synthez

import com.leon.synthez.core.SZBitmapFont
import com.leon.synthez.core.SZFrameResult
import com.leon.synthez.core.SZInputEvent
import com.leon.synthez.core.SZKeyCode
import com.leon.synthez.core.SZPoint
import com.leon.synthez.core.SZPointerKind
import com.leon.synthez.core.SZRect
import com.leon.synthez.core.SZSize
import com.leon.synthez.core.SZTextInputRequest
import com.leon.synthez.core.SZUI
import com.leon.synthez.widgets.SZTheme
import com.leon.synthez.widgets.button
import com.leon.synthez.widgets.pixel5x7
import com.leon.synthez.widgets.knob
import com.leon.synthez.widgets.list
import com.leon.synthez.widgets.scrollView
import com.leon.synthez.widgets.segmented
import com.leon.synthez.widgets.szOptions
import com.leon.synthez.widgets.textField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

private fun down(x: Int, y: Int) = SZInputEvent.PointerDown(SZPoint(x, y), SZPointerKind.TOUCH)
private fun move(x: Int, y: Int) = SZInputEvent.PointerMove(SZPoint(x, y), SZPointerKind.TOUCH)
private fun up(x: Int, y: Int) = SZInputEvent.PointerUp(SZPoint(x, y), SZPointerKind.TOUCH)

class WidgetsTest {
    @Test
    fun pixelFontCoversPrintableAscii() {
        val font = SZBitmapFont.pixel5x7()
        val fallback = font.glyphIndex('?'.code)
        for (cp in 0x20..0x7E) {
            if (cp == '?'.code) continue
            assertNotEquals("missing ${cp.toChar()}", fallback, font.glyphIndex(cp))
        }
        assertTrue(font.glyphs.all { it.height == 7 })
        assertEquals(11, font.measure("AB"))
    }

    @Test
    fun buttonClicksAcrossFrames() {
        val ui = SZUI(SZTheme.synthPalette())
        var clicks = 0
        fun frame(events: List<SZInputEvent>) {
            ui.frame(0.0, SZSize(100, 50), events) {
                if (button("GO", rect = SZRect(10, 10, 30, 11)).clicked) clicks++
            }
        }
        frame(emptyList())
        frame(listOf(down(20, 15)))
        frame(listOf(up(20, 15)))
        assertEquals(1, clicks)
    }

    @Test
    fun segmentedGlidesAndSettles() {
        val ui = SZUI(SZTheme.synthPalette())
        var selected = 0
        var frames = 0
        fun frame(t: Double, events: List<SZInputEvent> = emptyList()): SZFrameResult =
            ui.frame(t, SZSize(200, 50), events) {
                segmented(szOptions("A", "B", "C", "D"), selected, rect = SZRect(0, 0, 162, 13))?.let { selected = it }
            }
        frame(0.0)
        frame(0.01, listOf(down(130, 5), up(130, 5)))
        frame(0.015)
        assertEquals(3, selected)
        var t = 0.02
        while (frame(t).needsAnotherFrame && frames < 200) {
            t += 1.0 / 120
            frames++
        }
        assertTrue(frames in 4..199)
    }

    @Test
    fun stateChangedByInputIsDrawnNextFrame() {
        val ui = SZUI(SZTheme.synthPalette())
        var on = false
        var t = 0.0
        fun frame(events: List<SZInputEvent>): SZFrameResult {
            t += 1.0 / 60
            return ui.frame(t, SZSize(100, 40), events) {
                if (button("X", on = on, rect = SZRect(0, 0, 20, 11)).clicked) on = !on
            }
        }
        frame(emptyList())
        frame(listOf(down(5, 5)))
        val tap = frame(listOf(up(5, 5)))
        assertTrue(on)
        // The tap frame drew the button off; it must ask for the frame that draws it on.
        assertTrue(tap.needsAnotherFrame)
        assertTrue(frame(emptyList()).changed)
    }

    @Test
    fun deferredReleaseStillAsksForTheNextFrame() {
        val ui = SZUI(SZTheme.synthPalette())
        var on = false
        var t = 0.0
        fun frame(events: List<SZInputEvent>): SZFrameResult {
            t += 1.0 / 60
            return ui.frame(t, SZSize(100, 40), events) {
                if (button("X", on = on, rect = SZRect(0, 0, 20, 11)).clicked) on = !on
            }
        }
        frame(emptyList())
        // A whole tap in one batch: the release is applied a frame later,
        // from the deferred queue, and its click must still be drawn.
        frame(listOf(down(5, 5), up(5, 5)))
        val release = frame(emptyList())
        assertTrue(on)
        assertTrue(release.needsAnotherFrame)
        assertTrue(frame(emptyList()).changed)
    }

    private class ScrollHarness(contentHeight: Int, buttonCount: Int, buttonHeight: Int) {
        val ui = SZUI(SZTheme.synthPalette())
        var offset = SZPoint.ZERO
        var tapped: Int? = null
        private var t = 0.0
        private val height = contentHeight
        private val count = buttonCount
        private val buttonHeight = buttonHeight

        fun frame(events: List<SZInputEvent>): SZFrameResult {
            t += 1.0 / 60
            return ui.frame(t, SZSize(100, 100), events) {
                scrollView(content = SZSize(100, height), rect = SZRect(0, 0, 100, 100)) { scroll ->
                    offset = scroll.offset
                    for (i in 0 until count) {
                        if (button("B$i", key = i, rect = SZRect(0, i * 20, 80, buttonHeight)).clicked) tapped = i
                    }
                }
            }
        }
    }

    @Test
    fun scrollViewFlingsAndSettles() {
        val h = ScrollHarness(1000, 50, 11)
        h.frame(emptyList())
        h.frame(listOf(down(40, 80)))
        for (y in 70 downTo 20 step 10) h.frame(listOf(move(40, y)))
        assertTrue(h.offset.y > 40)
        h.frame(listOf(up(40, 20)))
        val released = h.offset.y
        var frames = 0
        while (h.frame(emptyList()).needsAnotherFrame && frames < 600) frames++
        assertTrue(h.offset.y > released + 20)
        assertTrue(h.offset.y <= 900)
        assertTrue(frames < 600)
    }

    @Test
    fun flingIntoAnEdgeOvershootsOnlyAFewPixels() {
        // Content barely taller than the view: a hard fling hits the bottom almost at once.
        val h = ScrollHarness(160, 8, 11)
        h.frame(emptyList())
        h.frame(listOf(down(40, 95)))
        for (y in 80 downTo 5 step 25) h.frame(listOf(move(40, y)))
        h.frame(listOf(up(40, 5)))
        var deepest = 0
        repeat(120) {
            h.frame(emptyList())
            deepest = maxOf(deepest, h.offset.y - 60)
        }
        assertTrue("overshoot $deepest", deepest <= 9)
        assertEquals(60, h.offset.y)
    }

    @Test
    fun knobTapStepsAndWraps() {
        val ui = SZUI(SZTheme.synthPalette())
        var value = 0.0
        var t = 0.0
        fun frame(events: List<SZInputEvent>) {
            // Input trickles in over frames, so let a few run after the events.
            for (batch in listOf(events, emptyList(), emptyList())) {
                ui.frame(t, SZSize(100, 100), batch) {
                    knob("SLEEP", value, steps = 3, tapToStep = true)?.let { value = it }
                }
                t += 0.5 // far enough apart that taps never count as double taps
            }
        }
        frame(emptyList())
        frame(listOf(down(10, 10), up(10, 10)))
        assertEquals(0.5, value, 1e-9)
        frame(listOf(down(10, 10), up(10, 10)))
        assertEquals(1.0, value, 1e-9)
        frame(listOf(down(10, 10), up(10, 10)))
        assertEquals(0.0, value, 1e-9)
        // A drag still turns it continuously, and is not also a tap.
        frame(listOf(down(10, 10)))
        frame(listOf(move(10, -40)))
        frame(listOf(up(10, -40)))
        assertEquals(0.5, value, 1e-9)
    }

    @Test
    fun listRevealScrollsTheRowToTheMiddleOnce() {
        val ui = SZUI(SZTheme.synthPalette())
        var reveal: Int? = 50
        var firstVisible = -1
        fun frame(t: Double) = ui.frame(t, SZSize(100, 100), emptyList()) {
            firstVisible = -1
            list(100, 10, null, fade = 0, reveal = reveal, rect = SZRect(0, 0, 100, 102)) { row ->
                if (firstVisible < 0) firstVisible = row.index
            }
        }
        var t = 0.0
        repeat(120) { frame(t); t += 1.0 / 60 }
        // Row 50 (y 500..510) centered in a 100-pixel viewport: the view starts near y 455.
        assertTrue("first visible $firstVisible", firstVisible in 44..46)
        // The same reveal again does not pull the list back after the user scrolls away.
        reveal = 50
        repeat(10) { frame(t); t += 1.0 / 60 }
        assertTrue(firstVisible in 44..46)
    }

    @Test
    fun dragInsideOneFrameStillScrolls() {
        val h = ScrollHarness(1000, 50, 11)
        h.frame(emptyList())
        // A hitch: the whole drag arrives between two frames.
        val events = ArrayList<SZInputEvent>()
        events.add(down(40, 90))
        for (y in 80 downTo 30 step 10) events.add(move(40, y))
        events.add(up(40, 30))
        var frames = 0
        var result = h.frame(events)
        while (result.needsAnotherFrame && frames < 300) {
            result = h.frame(emptyList())
            frames++
        }
        assertTrue(h.offset.y >= 50)
    }

    @Test
    fun pressDuringAFlingStopsItWithoutTapping() {
        val h = ScrollHarness(4000, 200, 18)
        h.frame(emptyList())
        h.frame(listOf(down(90, 90)))
        for (y in 80 downTo 10 step 14) h.frame(listOf(move(90, y)))
        h.frame(listOf(up(90, 10)))
        repeat(4) { h.frame(emptyList()) }
        val before = h.offset.y
        h.frame(emptyList())
        val moving = h.offset.y
        h.frame(listOf(down(40, 50)))
        h.frame(listOf(up(40, 50)))
        val stopped = h.offset.y
        repeat(30) { h.frame(emptyList()) }
        assertTrue("still flinging when pressed", moving > before + 5)
        assertNull(h.tapped)
        assertTrue(abs(stopped - moving) <= 1)
        assertTrue(abs(h.offset.y - stopped) <= 2)
    }

    @Test
    fun textFieldTypesAndCommits() {
        val ui = SZUI(SZTheme.synthPalette())
        var value = "AB"
        var request: SZTextInputRequest? = null
        fun frame(events: List<SZInputEvent>) {
            val result = ui.frame(0.0, SZSize(200, 50), events) {
                val r = textField(value, maxLength = 4, rect = SZRect(0, 0, 100, 13))
                if (r.committed) value = r.text
            }
            request = result.textInput
        }
        frame(emptyList())
        frame(listOf(down(50, 5), up(50, 5)))
        assertNotNull(request)
        frame(listOf(SZInputEvent.Text("CDEF"), SZInputEvent.Key(SZKeyCode.Backspace), SZInputEvent.Text("Z")))
        frame(listOf(SZInputEvent.Key(SZKeyCode.Enter)))
        assertEquals("ABCZ", value)
        frame(emptyList())
        assertNull(request)
    }
}
