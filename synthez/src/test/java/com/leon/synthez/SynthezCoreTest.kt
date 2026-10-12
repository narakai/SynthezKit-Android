package com.leon.synthez

import com.leon.synthez.core.SZAlignment
import com.leon.synthez.core.SZBitmap
import com.leon.synthez.core.SZBitmapFont
import com.leon.synthez.core.SZDrawList
import com.leon.synthez.core.SZFrameResult
import com.leon.synthez.core.SZFrameScheduler
import com.leon.synthez.core.SZID
import com.leon.synthez.core.SZInputEvent
import com.leon.synthez.core.SZInteraction
import com.leon.synthez.core.SZPalette
import com.leon.synthez.core.SZPatterns
import com.leon.synthez.core.SZPoint
import com.leon.synthez.core.SZPointerKind
import com.leon.synthez.core.SZRGBA
import com.leon.synthez.core.SZRect
import com.leon.synthez.core.SZResolution
import com.leon.synthez.core.SZScreen
import com.leon.synthez.core.SZSize
import com.leon.synthez.core.SZSurface
import com.leon.synthez.core.SZUI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

private fun makeUI() = SZUI(
    SZPalette(listOf(SZRGBA.hex(0x000000), SZRGBA.hex(0x444444), SZRGBA.hex(0x888888), SZRGBA.hex(0xFFFFFF))),
)

private val SIZE = SZSize(100, 80)

private fun down(x: Int, y: Int) = SZInputEvent.PointerDown(SZPoint(x, y), SZPointerKind.TOUCH)
private fun move(x: Int, y: Int) = SZInputEvent.PointerMove(SZPoint(x, y), SZPointerKind.TOUCH)
private fun up(x: Int, y: Int) = SZInputEvent.PointerUp(SZPoint(x, y), SZPointerKind.TOUCH)

class LayoutTest {
    @Test
    fun placeFlowsWithGapAndAlignment() {
        val ui = makeUI()
        val rects = ArrayList<SZRect>()
        ui.frame(0.0, SIZE, emptyList()) {
            gap = 2
            rects.add(place(10, 5))
            rects.add(place(20, 5))
            align = SZAlignment.CENTER
            rects.add(place(20, 5))
            align = SZAlignment.STRETCH
            rects.add(place(20, 5))
        }
        assertEquals(listOf(SZRect(0, 0, 10, 5), SZRect(0, 7, 20, 5), SZRect(40, 14, 20, 5), SZRect(0, 21, 100, 5)), rects)
    }

    @Test
    fun rowAdvancesByNaturalSize() {
        val ui = makeUI()
        var after = SZRect.ZERO
        val inRow = ArrayList<SZRect>()
        ui.frame(0.0, SIZE, emptyList()) {
            inset(4)
            row(gap = 3) {
                inRow.add(place(10, 6))
                inRow.add(place(10, 9))
            }
            after = place(5, 5)
        }
        assertEquals(listOf(SZRect(0, 0, 10, 6), SZRect(13, 0, 10, 9)), inRow)
        assertEquals(SZRect(4, 13, 5, 5), after)
    }

    @Test
    fun cutsShrinkBounds() {
        val ui = makeUI()
        val rects = ArrayList<SZRect>()
        var bounds = SZRect.ZERO
        ui.frame(0.0, SIZE, emptyList()) {
            rects.add(cutTop(10, gap = 2))
            rects.add(cutRight(20))
            rects.add(cutLeft(5, gap = 1))
            bounds = this.bounds
            rects.add(place(4, 4))
        }
        assertEquals(listOf(SZRect(0, 0, 100, 10), SZRect(80, 12, 20, 68), SZRect(0, 12, 5, 68), SZRect(6, 12, 4, 4)), rects)
        assertEquals(SZRect(6, 12, 74, 68), bounds)
    }
}

class StateTest {
    @Test
    fun positionalIDsAreStableAcrossFrames() {
        val ui = makeUI()
        val ids = ArrayList<List<SZID>>()
        for (i in 0 until 2) {
            val frame = ArrayList<SZID>()
            ui.frame(i.toDouble(), SIZE, emptyList()) {
                frame.add(makeKey())
                row { frame.add(makeKey()) }
                frame.add(makeKey())
                frame.add(makeKey("named"))
            }
            ids.add(frame)
        }
        assertEquals(ids[0], ids[1])
        assertEquals(4, ids[0].toSet().size)
        assertEquals(SZID.of("named"), ids[0][3])
    }

    @Test
    fun stateLivesWhileVisited() {
        val ui = makeUI()
        fun frame(show: Boolean): Int? {
            var value: Int? = null
            ui.frame(0.0, SIZE, emptyList()) {
                if (show) {
                    val state = state(key = "counter") { 0 }
                    state.value += 1
                    value = state.value
                }
            }
            return value
        }
        assertEquals(1, frame(true))
        assertEquals(2, frame(true))
        assertNull(frame(false))
        assertEquals(1, frame(true))
    }

    @Test
    fun animatedValueEasesAndRequestsFrames() {
        val ui = makeUI()
        var target = 0.0
        var value = 0.0
        var result = ui.frame(0.0, SIZE, emptyList()) { value = animatedValue(target, key = "a") }
        assertTrue(value == 0.0 && !result.needsAnotherFrame)
        target = 100.0
        var t = 0.0
        var frames = 0
        do {
            t += 1.0 / 60
            frames++
            result = ui.frame(t, SIZE, emptyList()) { value = animatedValue(target, key = "a") }
        } while (result.needsAnotherFrame && frames < 500)
        assertEquals(100.0, value, 0.0)
        assertTrue(frames in 6..99)
    }

    @Test
    fun animationAfterIdleStartsWithOneFrameStep() {
        val ui = makeUI()
        var target = 0.0
        var value = 0.0
        ui.frame(0.0, SIZE, emptyList()) { value = animatedValue(target, key = "a") }
        // Ten idle seconds pass with the frame loop paused.
        target = 100.0
        ui.frame(10.0, SIZE, emptyList()) { value = animatedValue(target, key = "a") }
        assertTrue(abs(value - 15) < 0.01)
        // Once moving, real elapsed time counts.
        ui.frame(10 + 2.0 / 60, SIZE, emptyList()) { value = animatedValue(target, key = "a") }
        assertTrue(value > 35 && value < 40)
    }
}

class InputTest {
    /** Draws a button-like rect and an optional overlay over it. */
    private fun frame(ui: SZUI, events: List<SZInputEvent>, overlay: Boolean = false, clip: SZRect? = null): Pair<SZInteraction, SZInteraction?> {
        var base = SZInteraction(SZID.ROOT)
        var top: SZInteraction? = null
        ui.frame(0.0, SIZE, events) {
            base = if (clip != null) {
                clip(clip) { interaction(SZRect(10, 10, 20, 20), key = "base") }
            } else {
                interaction(SZRect(10, 10, 20, 20), key = "base")
            }
            if (overlay) top = overlay { interaction(SZRect(0, 0, 25, 25), key = "top") }
        }
        return base to top
    }

    @Test
    fun tapClicksOnRelease() {
        val ui = makeUI()
        frame(ui, emptyList())
        val pressed = frame(ui, listOf(down(15, 15))).first
        assertTrue(pressed.pressed && pressed.held && pressed.hovered && !pressed.clicked)
        val moved = frame(ui, listOf(move(18, 19))).first
        assertTrue(moved.held && moved.dx == 3 && moved.dy == 4)
        val released = frame(ui, listOf(up(18, 19))).first
        assertTrue(released.released && released.clicked && !released.held)
        val after = frame(ui, emptyList()).first
        assertTrue(!after.hovered && !after.clicked)
    }

    @Test
    fun releaseOutsideDoesNotClick() {
        val ui = makeUI()
        frame(ui, emptyList())
        frame(ui, listOf(down(15, 15)))
        val released = frame(ui, listOf(up(60, 60))).first
        assertTrue(released.released && !released.clicked)
    }

    @Test
    fun overlayBlocksLowerLayer() {
        val ui = makeUI()
        frame(ui, emptyList(), overlay = true)
        val result = frame(ui, listOf(down(15, 15)), overlay = true)
        assertTrue(result.second?.pressed == true)
        assertFalse(result.first.pressed)
        // Outside the overlay the base still answers.
        frame(ui, listOf(up(15, 15)), overlay = true)
        val outside = frame(ui, listOf(down(28, 28)), overlay = true)
        assertTrue(outside.first.pressed)
    }

    @Test
    fun clippedPartDoesNotHit() {
        val ui = makeUI()
        val clip = SZRect(0, 0, 20, 100)
        frame(ui, emptyList(), clip = clip)
        assertFalse(frame(ui, listOf(down(25, 15)), clip = clip).first.pressed)
        frame(ui, listOf(up(25, 15)), clip = clip)
        assertTrue(frame(ui, listOf(down(15, 15)), clip = clip).first.pressed)
    }

    @Test
    fun doubleTapCountsPresses() {
        val ui = makeUI()
        frame(ui, emptyList())
        // A whole tap in one batch: the press shows this frame, the release next.
        val pressed = frame(ui, listOf(down(15, 15), up(15, 15))).first
        assertTrue(pressed.pressed && pressed.held && !pressed.clicked)
        assertTrue(ui.hasDeferredInput)
        val first = frame(ui, emptyList()).first
        assertTrue(first.clicked && first.presses == 1)
        val second = frame(ui, listOf(down(16, 15))).first
        assertTrue(second.doubleClicked)
    }
}

class RasterTest {
    @Test
    fun fillHonorsPatternAndClip() {
        val surface = SZSurface(8, 8)
        surface.fill(SZRect(-2, -2, 20, 20), 3, SZPatterns.HALF, SZRect(0, 0, 4, 4))
        var lit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (surface[x, y] == 3) {
                    lit++
                    assertTrue(x < 4 && y < 4)
                    assertTrue(SZPatterns.covers(SZPatterns.HALF, x, y))
                }
            }
        }
        assertEquals(8, lit)
    }

    @Test
    fun ditherLevelsNest() {
        for (level in 0 until 16) {
            val a = SZPatterns.dither(level)
            val b = SZPatterns.dither(level + 1)
            assertEquals(a, a and b)
            assertEquals(level + 1, Integer.bitCount(b))
        }
    }

    @Test
    fun textBlitsGlyphs() {
        val font = SZBitmapFont("t", listOf("A" to SZBitmap("#.", ".#"), "?" to SZBitmap("##")))
        val list = SZDrawList()
        list.begin(SZRect(0, 0, 6, 2), 0)
        val width = list.appendText("AA", font, SZPoint.ZERO, 2, 1, 0, 0)
        assertEquals(5, width)
        val surface = SZSurface(6, 2)
        surface.render(list)
        assertEquals(listOf(2, 0, 0, 2, 0, 0, 0, 2, 0, 0, 2, 0), surface.pixels.map { it.toInt() })
    }

    @Test
    fun overlayLayerDrawsOnTop() {
        val ui = makeUI()
        ui.frame(0.0, SZSize(4, 1), emptyList()) {
            overlay { fill(SZRect(0, 0, 2, 1), 3) }
            fill(SZRect(0, 0, 4, 1), 2)
        }
        val surface = SZSurface(4, 1)
        surface.render(ui.drawList)
        assertEquals(listOf(3, 3, 2, 2), surface.pixels.map { it.toInt() })
    }

    @Test
    fun identicalFramesAreUnchanged() {
        val ui = makeUI()
        val font = SZBitmapFont("t", listOf("?" to SZBitmap("#")))
        var text = "ab"
        fun frame(): SZFrameResult = ui.frame(0.0, SIZE, emptyList()) {
            fill(SZRect(0, 0, 10, 10), 1)
            text(text, SZPoint(2, 2), font, 3)
        }
        assertTrue(frame().changed)
        assertFalse(frame().changed)
        text = "abc"
        assertTrue(frame().changed)
        assertFalse(frame().changed)
    }

    @Test
    fun offscreenDrawingIsCulled() {
        val ui = makeUI()
        ui.frame(0.0, SIZE, emptyList()) {
            fill(SZRect(200, 0, 10, 10), 1)
            clip(SZRect(0, 0, 10, 10)) { fill(SZRect(20, 20, 5, 5), 1) }
        }
        assertEquals(0, ui.drawList.commandCount)
    }
}

class PaletteTest {
    @Test
    fun mixMapMovesTowardTarget() {
        val palette = SZPalette(listOf(SZRGBA.hex(0x000000), SZRGBA.hex(0x555555), SZRGBA.hex(0xAAAAAA), SZRGBA.hex(0xFFFFFF)))
        val full = palette.mixMap(0, 1.0)
        assertEquals(listOf(0, 0, 0, 0), (0 until 4).map { full[it] })
        val none = palette.mixMap(0, 0.0)
        assertEquals(listOf(0, 1, 2, 3), (0 until 4).map { none[it] })
        assertSame(full, palette.mixMap(0, 1.0))
    }

    @Test
    fun schedulerIdlesUntilInvalidated() {
        val scheduler = SZFrameScheduler()
        assertTrue(scheduler.isDue(0.0, false))
        val keepsTicking = scheduler.didDraw(0.0, SZFrameResult(needsAnotherFrame = false, changed = true))
        assertFalse(keepsTicking)
        assertFalse(scheduler.isDue(1.0, false))
        assertTrue(scheduler.isDue(1.0, true))
        scheduler.invalidate()
        assertTrue(scheduler.isDue(1.0, false))
    }

    @Test
    fun schedulerSettlesAfterChange() {
        val scheduler = SZFrameScheduler(settleDuration = 0.5)
        val keepsTicking = scheduler.didDraw(0.0, SZFrameResult(needsAnotherFrame = false, changed = true))
        assertTrue(keepsTicking)
        assertTrue(scheduler.isDue(0.4, false))
        assertFalse(scheduler.isDue(0.6, false))
    }

    @Test
    fun screenLetterboxes() {
        val screen = SZScreen.of(SZResolution.Fixed(640, 400), 1600.0, 1200.0)
        assertEquals(2.5, screen.scale, 0.0)
        assertEquals(100.0, screen.viewportY, 0.0)
        assertEquals(SZPoint(2, 1), screen.virtualPoint(5.0, 102.5))
    }
}
