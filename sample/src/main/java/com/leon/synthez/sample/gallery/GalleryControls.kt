package com.leon.synthez.sample.gallery

import com.leon.synthez.core.SZAlignment
import com.leon.synthez.core.SZAxis
import com.leon.synthez.core.SZContext
import com.leon.synthez.core.SZRect
import com.leon.synthez.core.SZSize
import com.leon.synthez.widgets.SZIcons
import com.leon.synthez.widgets.SZMenuItem
import com.leon.synthez.widgets.SZOption
import com.leon.synthez.widgets.SZScrubLook
import com.leon.synthez.widgets.button
import com.leon.synthez.widgets.card
import com.leon.synthez.widgets.checkbox
import com.leon.synthez.widgets.chipGroup
import com.leon.synthez.widgets.dropdown
import com.leon.synthez.widgets.iconButton
import com.leon.synthez.widgets.knob
import com.leon.synthez.widgets.label
import com.leon.synthez.widgets.scrubValue
import com.leon.synthez.widgets.segmented
import com.leon.synthez.widgets.slider
import com.leon.synthez.widgets.stepper
import com.leon.synthez.widgets.szOptions
import com.leon.synthez.widgets.textButton
import com.leon.synthez.widgets.theme
import kotlin.math.abs
import kotlin.math.max

internal fun SZGallery.drawControlsLeft(ctx: SZContext) {
    with(ctx) {
        val theme = theme
        card("BUTTONS") {
            gap = 5
            row(height = 11, gap = 3, align = SZAlignment.CENTER) {
                if (button("SAVE", hint = "WRITE THE PROJECT").clicked) post("SAVED")
                if (button("ARM", on = on, hint = "A LATCHING BUTTON").clicked) on = !on
                button("LOCKED", disabled = true)
                if (iconButton(SZIcons.gear, "SETTINGS", hint = "AN ICON BUTTON").clicked) post("SETTINGS")
                if (iconButton(SZIcons.star, "FAVORITE", on = 99 in starred, hint = "TOGGLE A STAR").clicked) starred.toggle(99)
            }
            row(height = 9, gap = 8, align = SZAlignment.CENTER) {
                if (textButton("READ THE DOCS", hint = "A TEXT BUTTON").clicked) post("DOCS")
                if (textButton("UNDO", theme.text).clicked) post("UNDONE")
            }
        }

        card("CHIPS") {
            gap = 5
            val names = szOptions("AMBIENT", "TECHNO", "HOUSE", "ELECTRO", "DRONE", "IDM", "DUB", "GLITCH")
            chipGroup(names, genres)?.let { genres.toggle(it) }
            val icons = listOf(
                SZOption("SINE", SZIcons.sine),
                SZOption("SAW", SZIcons.saw),
                SZOption("SQUARE", SZIcons.square),
                SZOption("NOISE", SZIcons.noise),
            )
            row(gap = 6) {
                column(width = 4 * 20) {
                    chipGroup(icons, setOf(waveChip), key = "wave-chips")?.let { waveChip = it }
                }
                label(icons[waveChip].label, theme.textDim, rect = place(40, 11))
            }
        }

        card("TOGGLES") {
            gap = 4
            if (checkbox("MUTE", mute, hint = "SILENCE THE CHANNEL").clicked) mute = !mute
            if (checkbox("SOLO", solo, hint = "ONLY THIS CHANNEL").clicked) solo = !solo
            checkbox("ALWAYS ON", checked = true, disabled = true)
            segmented(szOptions("1/4", "1/8", "1/16", "1/32"), division, key = "division")?.let { division = it }
        }

        card("STEPPERS AND SCRUBBING") {
            gap = 5
            val colors = listOf("EMBER", "RUST", "SAND", "BONE", "ASH")
            val swatches = listOf(theme.accent, theme.accentDim, theme.textDim, theme.paper, theme.lineStrong)
            row(height = 13, gap = 6, align = SZAlignment.CENTER) {
                val step = stepper(
                    colors[colorIndex],
                    swatches[colorIndex],
                    width = 80,
                    previousDisabled = colorIndex == 0,
                    nextDisabled = colorIndex == colors.size - 1,
                    hint = "PICK A COLOR",
                )
                colorIndex = (colorIndex + step).coerceIn(0, colors.size - 1)
                val octaveStep = stepper("OCT $octave", width = 64, previousDisabled = octave == 0, nextDisabled = octave == 8, hint = "OCTAVE", key = "octave")
                octave = (octave + octaveStep).coerceIn(0, 8)
            }
            row(height = 11, gap = 5, align = SZAlignment.CENTER) {
                label("BPM", theme.textDim)
                scrubValue(bpm, 40.0..240.0, look = SZScrubLook.UNDERLINE, label = "BPM", hint = "DRAG SIDEWAYS")?.let { bpm = it }
                label("SWING", theme.textDim)
                scrubValue(
                    swing,
                    0.0..1.0,
                    step = 0.01,
                    look = SZScrubLook.OUTLINE,
                    dragPixels = 2,
                    width = 36,
                    label = "SWING",
                    hint = "DRAG SIDEWAYS",
                    key = "swing",
                )?.let { swing = it }
            }
        }
    }
}

internal fun SZGallery.drawControlsRight(ctx: SZContext) {
    with(ctx) {
        val theme = theme
        card("MENUS") {
            gap = 5
            val types = listOf(SZMenuItem("LOWPASS"), SZMenuItem("HIGHPASS"), SZMenuItem("BANDPASS"), SZMenuItem("NOTCH"), SZMenuItem("COMB", disabled = true))
            row(height = 13, gap = 6, align = SZAlignment.CENTER) {
                label("FILTER", theme.textDim)
                dropdown(types, filterType, width = 80, hint = "FILTER TYPE")?.let {
                    filterType = it
                    post("FILTER: ${types[it].label}")
                }
            }
        }

        card("KNOBS") {
            row(gap = 6) {
                val titles = listOf("CUTOFF", "RES", "DRIVE", "PAN", "STEP")
                for (i in knobs.indices) {
                    val bipolar = i == 3
                    val display: String? = when (i) {
                        3 -> if (knobs[i] == 0.5) "C" else (if (knobs[i] < 0.5) "L" else "R") + (abs(knobs[i] - 0.5) * 200).rnd()
                        4 -> ((knobs[i] * 7).rnd() + 1).toString()
                        else -> null
                    }
                    knob(
                        titles[i],
                        knobs[i],
                        bipolar = bipolar,
                        steps = if (i == 4) 8 else null,
                        reset = 0.5,
                        text = display,
                        hint = "DRAG UP OR DOWN; DOUBLE-TAP RESETS",
                        key = "knob-$i",
                    )?.let { knobs[i] = it }
                }
            }
        }

        card("SLIDERS") {
            gap = 5
            row(height = 9, gap = 6, align = SZAlignment.CENTER) {
                label("CUTOFF", theme.textDim, rect = place(38, 9))
                slider(cutoff, reset = 0.35, length = bounds.width - cursor.x - 30, label = "CUTOFF", hint = "DOUBLE-TAP RESETS")?.let { cutoff = it }
                label((cutoff * 100).toInt().toString(), theme.text)
            }
            row(height = 9, gap = 6, align = SZAlignment.CENTER) {
                label("BAL", theme.textDim, rect = place(38, 9))
                slider(balance, -1.0..1.0, bipolar = true, reset = 0.0, length = bounds.width - cursor.x - 30, label = "BALANCE")?.let { balance = it }
                label((balance * 100).toInt().toString(), theme.text)
            }
            row(height = 56, gap = 8) {
                val bands = listOf("LOW", "MID", "HI", "AIR")
                for (i in eq.indices) {
                    column(width = 18, gap = 2, align = SZAlignment.CENTER) {
                        slider(eq[i], step = 1.0 / 24, axis = SZAxis.VERTICAL, reset = 0.5, length = 44, label = bands[i], key = "eq-$i")?.let { eq[i] = it }
                        label(bands[i], theme.textDim)
                    }
                }
            }
        }

        card("PADS") {
            val columns = max((bounds.width + 3) / 31, 1)
            val cell = SZSize(28, 11)
            var y = 0
            for (r in 0 until (gridCount + columns - 1) / columns) {
                for (c in 0 until columns) {
                    val index = r * columns + c
                    if (index >= gridCount) break
                    val rect = SZRect(c * (cell.width + 3), y, cell.width, cell.height)
                    if (button(index.toString(), on = pad == index, height = cell.height, key = 1000 + index, rect = rect).clicked) pad = index
                }
                y += cell.height + 3
            }
            place(SZSize(0, max(y - 3, 0)))
        }
    }
}
