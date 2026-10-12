package com.leon.synthez.sample.gallery

import com.leon.synthez.core.SZAlignment
import com.leon.synthez.core.SZBitmapFont
import com.leon.synthez.core.SZGlyphSource
import com.leon.synthez.core.SZAxis
import com.leon.synthez.core.SZCRTSettings
import com.leon.synthez.core.SZContext
import com.leon.synthez.core.SZRect
import com.leon.synthez.core.SZResolution
import com.leon.synthez.widgets.SZIcons
import com.leon.synthez.widgets.SZTheme
import com.leon.synthez.widgets.SZThemeKey
import com.leon.synthez.widgets.pixel5x7
import com.leon.synthez.widgets.iconButton
import com.leon.synthez.widgets.label
import com.leon.synthez.widgets.scrollView
import com.leon.synthez.widgets.statusLine
import com.leon.synthez.widgets.tabs
import com.leon.synthez.widgets.theme
import com.leon.synthez.widgets.tooltip
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * The demo app shared by the sample and the tests: five pages behind a tab
 * bar, a status line and tooltips.
 */
class SZGallery(var gridCount: Int = 32, cjk: SZGlyphSource? = null) {
    enum class Page(val title: String) {
        CORE("CORE"),
        CONTROLS("CONTROLS"),
        TEXT("TEXT"),
        SCROLL("SCROLL"),
        CRT("CRT"),
        JAPANESE("JAPANESE"),
    }

    /** The theme for the Japanese page: 5×7 for ASCII, the CJK source for the rest. Kept for the font's glyph cache. */
    internal val japaneseTheme: SZTheme? = cjk?.let { SZTheme.synth(SZBitmapFont.pixel5x7(it)) }

    var page = Page.CORE
    var crt = SZCRTSettings()

    // Status messages.
    internal var message: String? = null
    internal var messageSerial = 0

    // Core page.
    internal var count = 0
    internal var loop = true
    internal var swapped = false
    internal var waveform = 0
    internal var playing = false
    internal var animateFilters = false
    internal val drums = mutableSetOf(0, 2)
    internal var sheetOpen = false
    internal var picked: String? = null
    internal val starred = mutableSetOf(1)
    internal var menuOpen = false

    // Controls page.
    internal val genres = mutableSetOf(0, 3)
    internal var waveChip = 1
    internal var mute = false
    internal var solo = true
    internal var colorIndex = 2
    internal var octave = 4
    internal var division = 0
    internal var bpm = 120.0
    internal var swing = 0.5
    internal var filterType: Int? = 0
    internal val knobs = doubleArrayOf(0.62, 0.3, 0.45, 0.5, 0.25)
    internal var cutoff = 0.35
    internal var balance = 0.0
    internal val eq = doubleArrayOf(0.5, 0.7, 0.4, 0.6)
    internal var pad = 0
    internal var on = true

    // Text page.
    internal var title = "UNTITLED SONG"
    internal var name = ""
    internal var digits = "128"
    internal var note = "A LONG NOTE THAT KEEPS GOING PAST THE EDGE OF ITS FIELD"
    internal val log = ArrayList<String>()
    internal var alignment = 0

    // Japanese page.
    internal var station: Int? = 0
    internal var japanesePlaying = false
    internal var japaneseMode = 0
    internal var captionsOn = true
    internal var captionSize = 1

    // Scroll page.
    internal var selectedRow: Int? = 3
    internal val channels = DoubleArray(16) { 0.3 + 0.4 * abs(sin(it * 1.7)) }

    internal fun post(text: String) {
        message = text
        messageSerial++
    }

    fun draw(ctx: SZContext) {
        with(ctx) {
            val theme = theme
            fill(bounds, theme.background)
            inset(6, 6, 0, 6)

            // Header: name, page count and the CRT switch.
            val header = cutTop(11, gap = 4)
            region(header) {
                val toggle = cutRight(11)
                if (iconButton(SZIcons.screen, "CRT", on = crt.isEnabled, hint = "TOGGLE THE CRT FILTER", key = "crt-toggle", rect = toggle).clicked) {
                    crt = crt.copy(isEnabled = !crt.isEnabled)
                    post(if (crt.isEnabled) "CRT ON" else "CRT OFF")
                }
                flow = SZAxis.HORIZONTAL
                gap = 6
                align = SZAlignment.CENTER
                label("SYNTH-KIT", theme.accent)
                label("IMMEDIATE MODE PIXEL UI", theme.textDim)
            }

            tabs(Page.entries.map { it.title }, page.ordinal, key = "tabs", rect = cutTop(13, gap = 5))?.let { page = Page.entries[it] }

            val status = cutBottom(13, gap = 4)
            val body = bounds
            val isWide = body.width >= 560

            if (page == Page.SCROLL) {
                region(body, key = "page-scroll") { drawScrollPage(this, isWide) }
            } else {
                scrollView(fade = 10, key = "page-${page.ordinal}", rect = body) {
                    gap = 6
                    drawPage(this, isWide)
                    space(6)
                }
            }

            statusLine(
                "READY - HOLD ANYTHING FOR A HINT",
                message,
                messageSerial,
                rect = SZRect(status.x - 6, status.y, status.width + 12, status.height),
            )
            tooltip()
        }
    }

    private fun drawPage(ctx: SZContext, isWide: Boolean) {
        val columns: List<(SZContext) -> Unit> = when (page) {
            Page.CORE -> listOf(this::drawCoreLeft, this::drawCoreRight)
            Page.CONTROLS -> listOf(this::drawControlsLeft, this::drawControlsRight)
            Page.TEXT -> listOf(this::drawTextLeft, this::drawTextRight)
            Page.CRT -> listOf(this::drawCRTLeft, this::drawCRTRight)
            Page.JAPANESE -> listOf(this::drawJapaneseLeft, this::drawJapaneseRight)
            Page.SCROLL -> emptyList()
        }
        // The Japanese page draws with its own font; the frame around it keeps 5×7.
        val ui = ctx.ui
        val saved = ui.environment(SZThemeKey)
        if (page == Page.JAPANESE) japaneseTheme?.let { ui.setEnvironment(SZThemeKey, it) }
        if (isWide) {
            ctx.row(gap = 8) {
                val width = (bounds.width - 8) / 2
                for (draw in columns) column(width = width, gap = 6) { draw(this) }
            }
        } else {
            for (draw in columns) draw(ctx)
        }
        ui.setEnvironment(SZThemeKey, saved)
    }

    companion object {
        val RESOLUTION = SZResolution.Fixed(640, 400)
    }
}

/** Rounds half away from zero, like Swift's `rounded()`. */
internal fun Double.rnd(): Int = (if (this >= 0) floor(this + 0.5) else -floor(-this + 0.5)).toInt()

internal fun MutableSet<Int>.toggle(value: Int) {
    if (!remove(value)) add(value)
}
