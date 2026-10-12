package com.leon.synthez.sample.gallery

import com.leon.synthez.core.SZAlignment
import com.leon.synthez.core.SZAxis
import com.leon.synthez.core.SZCRTMask
import com.leon.synthez.core.SZCRTSettings
import com.leon.synthez.core.SZContext
import com.leon.synthez.core.SZPoint
import com.leon.synthez.core.SZRect
import com.leon.synthez.core.SZSize
import com.leon.synthez.widgets.SZScrollAxes
import com.leon.synthez.widgets.button
import com.leon.synthez.widgets.card
import com.leon.synthez.widgets.checkbox
import com.leon.synthez.widgets.label
import com.leon.synthez.widgets.list
import com.leon.synthez.widgets.paragraph
import com.leon.synthez.widgets.scrollView
import com.leon.synthez.widgets.segmented
import com.leon.synthez.widgets.slider
import com.leon.synthez.widgets.szOptions
import com.leon.synthez.widgets.textButton
import com.leon.synthez.widgets.textField
import com.leon.synthez.widgets.theme
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// Text page

internal fun SZGallery.drawTextLeft(ctx: SZContext) {
    with(ctx) {
        val theme = theme
        card("TITLE - DOUBLE SIZE") {
            val result = textField(title, "NAME THE SONG", maxLength = 24, scale = 2, label = "TITLE", hint = "TAP TO EDIT", key = "title")
            if (result.committed) {
                title = result.text
                log.add("TITLE: ${result.text}")
                post("TITLE SAVED")
            }
        }

        card("FIELDS") {
            gap = 5
            row(height = 13, gap = 6, align = SZAlignment.CENTER) {
                label("NAME", theme.textDim, rect = place(30, 13))
                val result = textField(name, "16 CHARS MAX", maxLength = 16, width = bounds.width - cursor.x - 6, label = "NAME", key = "name")
                if (result.committed) {
                    name = result.text
                    log.add("NAME: ${result.text.ifEmpty { "-" }}")
                }
            }
            row(height = 13, gap = 6, align = SZAlignment.CENTER) {
                label("BPM", theme.textDim, rect = place(30, 13))
                val result = textField(
                    digits,
                    "DIGITS",
                    maxLength = 3,
                    allowed = { Character.isDigit(it) },
                    numeric = true,
                    width = 40,
                    label = "DIGITS",
                    hint = "NUMBERS ONLY",
                    key = "digits",
                )
                if (result.committed) {
                    digits = result.text
                    log.add("BPM: ${result.text}")
                }
                label("NUMBERS ONLY", theme.textDim)
            }
            row(height = 13, gap = 6, align = SZAlignment.CENTER) {
                label("NOTE", theme.textDim, rect = place(30, 13))
                val result = textField(note, width = bounds.width - cursor.x - 6, label = "NOTE", hint = "LONG TEXT SCROLLS", key = "note")
                if (result.committed) {
                    note = result.text
                    log.add("NOTE SAVED")
                }
            }
        }
    }
}

internal fun SZGallery.drawTextRight(ctx: SZContext) {
    with(ctx) {
        val theme = theme
        card("LOG - LAST SUBMISSIONS") {
            gap = 2
            if (log.isEmpty()) label("NOTHING YET - EDIT A FIELD", theme.textDim)
            val recent = log.takeLast(5)
            recent.forEachIndexed { i, entry ->
                label("${log.size - recent.size + i + 1}. $entry", if (i == recent.size - 1) theme.paper else theme.text)
            }
            if (log.isNotEmpty() && textButton("CLEAR").clicked) log.clear()
        }

        card("PARAGRAPH - WRAPS TO FIT") {
            gap = 5
            segmented(szOptions("LEFT", "CENTER", "RIGHT"), alignment, key = "align")?.let { alignment = it }
            val textAlign = listOf(SZAlignment.START, SZAlignment.CENTER, SZAlignment.END)[alignment]
            paragraph(
                "PIXEL TEXT BREAKS AT SPACES. A LAYOUT IS KEPT WHILE ITS TEXT AND WIDTH STAY THE SAME, SO WRAPPING COSTS NOTHING ON IDLE FRAMES.",
                theme.text,
                align = textAlign,
            )
        }
    }
}

// Scroll page

internal fun SZGallery.drawScrollPage(ctx: SZContext, isWide: Boolean) {
    with(ctx) {
        val theme = theme
        val total = bounds.height
        val listHeight = if (isWide) total else total * 45 / 100

        val listArea = if (isWide) cutLeft((bounds.width - 8) / 2, gap = 8) else cutTop(listHeight, gap = 6)
        region(listArea) {
            val head = cutTop(9, gap = 3)
            text("LIST - 10,000 ROWS, ONLY VISIBLE ONES BUILT", head.origin, theme.font, theme.textDim)
            val tapped = list(10000, 13, selectedRow, key = "big-list", rect = bounds) { info ->
                val level = 0.5 + 0.5 * sin(info.index * 0.37) * cos(info.index * 0.11)
                val ink = if (info.selected) theme.paper else theme.text
                text(String.format(Locale.ROOT, "TRACK %05d", info.index + 1), SZPoint(6, 3), theme.font, ink)
                val meter = SZRect(bounds.width - 70, 4, 60, 5)
                fill(meter, theme.background)
                fill(SZRect(meter.x, meter.y, (meter.width * level).toInt(), meter.height), if (info.selected) theme.accentBright else theme.accentDim)
            }
            tapped?.let {
                selectedRow = it
                post(String.format(Locale.ROOT, "TRACK %05d", it + 1))
            }
        }

        val channelHeight = 78
        val channelArea = cutTop(channelHeight + 12, gap = 6)
        region(channelArea) {
            val head = cutTop(9, gap = 3)
            text("CHANNELS - SNAPS TO A STRIP", head.origin, theme.font, theme.textDim)
            val frame = bounds
            fill(frame, theme.panel)
            stroke(frame, theme.line)
            val strip = 44
            scrollView(
                SZScrollAxes.HORIZONTAL,
                SZSize(channels.size * strip + 4, frame.height - 2),
                snap = SZSize(strip, 0),
                fade = 20,
                key = "channels",
                rect = frame.inset(1),
            ) {
                for (i in channels.indices) {
                    val x = 4 + i * strip
                    region(SZRect(x, 4, strip - 6, frame.height - 10), key = "channel-$i") {
                        fill(bounds, theme.background)
                        stroke(bounds, theme.lineStrong)
                        text(String.format(Locale.ROOT, "CH%02d", i + 1), SZPoint(4, 3), theme.font, theme.textDim)
                        slider(
                            channels[i],
                            axis = SZAxis.VERTICAL,
                            reset = 0.5,
                            label = String.format(Locale.ROOT, "CHANNEL %d", i + 1),
                            key = "fader-$i",
                            rect = SZRect((bounds.width - 9) / 2, 13, 9, bounds.height - 26),
                        )?.let { channels[i] = it }
                        val db = (channels[i] * 60 - 48).rnd().toString()
                        text(db, SZPoint((bounds.width - theme.font.measure(db)) / 2, bounds.height - 10), theme.font, theme.text)
                    }
                }
            }
        }

        region(bounds) {
            val head = cutTop(9, gap = 3)
            text("SELF-MEASURED - CONTENT SIZES ITSELF", head.origin, theme.font, theme.textDim)
            val frame = bounds
            fill(frame, theme.panel)
            stroke(frame, theme.line)
            scrollView(fade = 12, key = "notes", rect = frame.inset(1)) {
                inset(4, 8, 4, 6)
                gap = 6
                val notes = listOf(
                    "IMMEDIATE MODE: THE WHOLE SCREEN IS DESCRIBED AGAIN EVERY FRAME, AND ONLY WHAT CHANGED IS DRAWN.",
                    "THE SCROLL VIEW MEASURES THIS TEXT AS IT IS LAID OUT AND USES THE SIZE NEXT FRAME.",
                    "FLING IT: VELOCITY DECAYS LIKE A NATIVE LIST, AND THE EDGES STRETCH AND SPRING BACK.",
                    "THE FADE AT EACH EDGE GROWS WITH HOW MUCH CONTENT HIDES BEYOND IT.",
                    "WHEN NOTHING MOVES, THE FRAME LOOP STOPS AND THE CANVAS COSTS NOTHING.",
                )
                notes.forEachIndexed { i, entry ->
                    row(gap = 4) {
                        label(String.format(Locale.ROOT, "%02d", i + 1), theme.accent, rect = place(12, 7))
                        column(width = bounds.width - 16) { paragraph(entry, theme.text) }
                    }
                }
            }
        }
    }
}

// CRT page

internal fun SZGallery.drawCRTLeft(ctx: SZContext) {
    with(ctx) {
        val theme = theme
        card("CRT - PHYSICAL MODEL") {
            gap = 4
            row(height = 11, gap = 6, align = SZAlignment.CENTER) {
                if (checkbox("ENABLED", crt.isEnabled, hint = "RUN THE CRT PIPELINE").clicked) crt = crt.copy(isEnabled = !crt.isEnabled)
                if (button("DEFAULTS", hint = "RESTORE EVERY PARAMETER").clicked) {
                    crt = SZCRTSettings(isEnabled = crt.isEnabled)
                    post("CRT DEFAULTS")
                }
            }
            segmented(szOptions("FLAT", "APERTURE", "SLOT", "SHADOW"), crt.mask.ordinal, key = "mask")?.let { crt = crt.copy(mask = SZCRTMask.entries[it]) }
            fun parameter(name: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double? = null, format: String = "%.2f", set: (Double) -> Unit) {
                row(height = 9, gap = 6, align = SZAlignment.CENTER) {
                    label(name, theme.textDim, rect = place(56, 9))
                    slider(value, range, step, length = bounds.width - cursor.x - 34, label = name, key = "crt-$name")?.let(set)
                    label(String.format(Locale.ROOT, format, value), theme.text)
                }
            }
            parameter("SCANLINES", crt.scanlines, 0.0..1.0) { crt = crt.copy(scanlines = it) }
            parameter("BEAM DIM", crt.beamDim, 0.1..0.6) { crt = crt.copy(beamDim = it) }
            parameter("BEAM HOT", crt.beamBright, 0.2..0.9) { crt = crt.copy(beamBright = it) }
            parameter("MASK", crt.maskStrength, 0.0..1.0) { crt = crt.copy(maskStrength = it) }
            parameter("HALATION", crt.halation, 0.0..1.0) { crt = crt.copy(halation = it) }
            parameter("SPREAD", crt.spread.toDouble(), 1.0..6.0, step = 1.0, format = "%.0f") { crt = crt.copy(spread = it.toInt()) }
            parameter("VIGNETTE", crt.vignette, 0.0..1.0) { crt = crt.copy(vignette = it) }
            parameter("AFTERGLOW", crt.persistence, 0.0..0.3) { crt = crt.copy(persistence = it) }
            parameter("BRIGHTNESS", crt.brightness, 0.6..2.0) { crt = crt.copy(brightness = it) }
        }
    }
}

internal fun SZGallery.drawCRTRight(ctx: SZContext) {
    with(ctx) {
        val theme = theme
        card("TEST PATTERN") {
            gap = 4
            // Every grey of the palette, then the accents.
            val greyRamp = place(SZSize(0, 12))
            val greys = 14
            for (i in 0 until greys) {
                val x0 = greyRamp.x + greyRamp.width * i / greys
                val x1 = greyRamp.x + greyRamp.width * (i + 1) / greys
                fill(SZRect(x0, greyRamp.y, x1 - x0, greyRamp.height), i)
            }
            val accents = place(SZSize(0, 12))
            for (i in 0 until 4) {
                val x0 = accents.x + accents.width * i / 4
                val x1 = accents.x + accents.width * (i + 1) / 4
                fill(SZRect(x0, accents.y, x1 - x0, accents.height), 14 + i)
            }
            row(height = 7, gap = 6) {
                for (color in listOf(theme.textDim, theme.text, theme.paper, theme.accent, theme.accentBright)) label("TEXT", color)
            }
            // Fine lines, a white block, a circle and a sweeping bar for the afterglow.
            val field = place(SZSize(0, 44))
            fill(field, 0)
            for (i in 0 until 6) {
                fill(SZRect(field.x + 4 + i * 4, field.y + 4, 1, 36), theme.paper)
                fill(SZRect(field.x + 30, field.y + 4 + i * 4, 26, 1), theme.paper)
            }
            fill(SZRect(field.x + 62, field.y + 6, 20, 20), theme.paper)
            circle(SZPoint(field.x + 104, field.y + 22), 16, theme.accent, filled = false)
            circle(SZPoint(field.x + 104, field.y + 22), 6, theme.accentBright)
            val sweep = field.x + 130 + ((sin(time * 2.4) + 1) / 2 * max(field.width - 140, 1)).rnd()
            fill(SZRect(sweep, field.y + 2, 6, field.height - 4), theme.paper)
            requestFrame()
        }
    }
}
