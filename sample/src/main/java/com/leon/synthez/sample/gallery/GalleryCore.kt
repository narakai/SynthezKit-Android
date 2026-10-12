package com.leon.synthez.sample.gallery

import com.leon.synthez.core.SZAccessibilityTraits
import com.leon.synthez.core.SZAlignment
import com.leon.synthez.core.SZContext
import com.leon.synthez.core.SZEdge
import com.leon.synthez.core.SZFilterBlend
import com.leon.synthez.core.SZPatterns
import com.leon.synthez.core.SZPoint
import com.leon.synthez.core.SZPointerKind
import com.leon.synthez.core.SZRect
import com.leon.synthez.core.SZSize
import com.leon.synthez.core.SZStrengthField
import com.leon.synthez.core.accessibility
import com.leon.synthez.core.filter
import com.leon.synthez.core.ramp
import com.leon.synthez.widgets.SZIcons
import com.leon.synthez.widgets.SZMenuItem
import com.leon.synthez.widgets.SZOption
import com.leon.synthez.widgets.SZScrollAxes
import com.leon.synthez.widgets.button
import com.leon.synthez.widgets.card
import com.leon.synthez.widgets.checkbox
import com.leon.synthez.widgets.iconButton
import com.leon.synthez.widgets.label
import com.leon.synthez.widgets.menu
import com.leon.synthez.widgets.paragraph
import com.leon.synthez.widgets.scrollView
import com.leon.synthez.widgets.segmented
import com.leon.synthez.widgets.sheet
import com.leon.synthez.widgets.theme
import java.util.Locale
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A counter that keeps its count in UI state: by call position, or by key. */
private fun SZContext.counter(key: Any?) {
    val theme = theme
    val count = state(key = key) { 0 }
    row(height = 11, gap = 2, align = SZAlignment.CENTER) {
        if (iconButton(SZIcons.minus, "DECREMENT", size = 11).clicked) count.value -= 1
        val shown = String.format(Locale.ROOT, "%02d", count.value)
        label(shown, if (key == null) theme.text else theme.accentBright, rect = place(14, 11))
        if (iconButton(SZIcons.plus, "INCREMENT", size = 11).clicked) count.value += 1
    }
}

internal fun SZGallery.drawCoreLeft(ctx: SZContext) {
    with(ctx) {
        val theme = theme
        card("FLOW - NATURAL SIZES IN A ROW") {
            gap = 5
            row(height = 11, gap = 3, align = SZAlignment.CENTER) {
                if (button("-", hint = "ONE LESS").clicked) count--
                label(count.toString(), theme.paper, rect = place(16, 11))
                if (button("+", hint = "ONE MORE").clicked) count++
                if (button("RESET", disabled = count == 0, hint = "BACK TO ZERO").clicked) {
                    count = 0
                    post("COUNTER RESET")
                }
                if (button("LOOP", on = loop, hint = "A BUTTON THAT STAYS ON").clicked) loop = !loop
            }
            paragraph("EACH CONTROL ASKS ITS ROW FOR ITS NATURAL SIZE; THE ROW GROWS TO FIT.", theme.textDim)
        }

        card("STATE - PER CALL SITE OR BY KEY") {
            gap = 4
            val names = if (swapped) listOf("RIGHT", "LEFT") else listOf("LEFT", "RIGHT")
            for (side in names) {
                row(height = 11, gap = 6, align = SZAlignment.CENTER) {
                    label(side, theme.textDim, rect = place(34, 11))
                    counter(null)
                    space(6)
                    counter("counter:$side")
                }
            }
            row(height = 11, gap = 6, align = SZAlignment.CENTER) {
                if (button("SWAP", on = swapped, hint = "KEYED COUNTS FOLLOW THEIR ROW").clicked) swapped = !swapped
                label("POSITION / KEY", theme.textDim)
            }
        }

        card("CUTS - SLICE OFF THE EDGES") {
            gap = 4
            region(place(SZSize(0, 11))) {
                button("DELETE", disabled = true, rect = cutRight(38, gap = 3))
                if (button("COPY", hint = "DUPLICATE THE TRACK", rect = cutRight(30, gap = 3)).clicked) post("TRACK COPIED")
                if (button("NEW", hint = "ADD A TRACK", rect = cutRight(26, gap = 6)).clicked) post("TRACK ADDED")
                label("TRACKS 05/16", theme.text, rect = bounds)
            }
            paragraph("BUTTONS CUT FROM THE RIGHT; THE LABEL GETS WHAT IS LEFT.", theme.textDim)
        }

        card("FILTERS - READS WHAT IS DRAWN") {
            gap = 4
            if (checkbox("ANIMATE STRENGTH", animateFilters, hint = "SWEEP THE FILTERS OVER TIME").clicked) animateFilters = !animateFilters
            val strength = if (animateFilters) 0.5 + 0.5 * sin(time * 2) else 1.0
            if (animateFilters) requestFrame()
            row(height = 34, gap = 4) {
                val width = (bounds.width - 12) / 4
                listOf("DITHER", "STEPS", "RADIAL", "MOSAIC").forEachIndexed { i, filterName ->
                    val r = place(SZSize(width, 34))
                    val swatch = SZRect(r.x, r.y, r.width, 24)
                    fill(swatch, theme.accent)
                    text("SYNTH", SZPoint(swatch.x + 3, swatch.y + 5), theme.font, theme.paper, 2)
                    when (i) {
                        0 -> filter(swatch, ramp(swatch, SZEdge.RIGHT, strength), theme.background, SZFilterBlend.DITHER)
                        1 -> filter(swatch, ramp(swatch, SZEdge.RIGHT, strength), theme.background, SZFilterBlend.STEPS)
                        2 -> {
                            val center = SZPoint(swatch.x + swatch.width / 2, swatch.y + swatch.height / 2)
                            filter(swatch, SZStrengthField.Radial(center, 4.0, swatch.width / 2.0, 0.0, strength), theme.background)
                        }
                        else -> mosaic(swatch, max((strength * 4).rnd(), 1))
                    }
                    text(filterName, SZPoint(r.x, r.y + 27), theme.font, theme.textDim)
                }
            }
        }
    }
}

internal fun SZGallery.drawCoreRight(ctx: SZContext) {
    with(ctx) {
        val theme = theme
        card("ANIMATION - A GLIDING LIGHT") {
            gap = 5
            val waves = listOf(
                SZOption("SINE", SZIcons.sine, "A PURE TONE"),
                SZOption("SAW", SZIcons.saw, "BRIGHT AND BUZZY"),
                SZOption("SQUARE", SZIcons.square, "HOLLOW, LIKE A CLARINET"),
                SZOption("NOISE", SZIcons.noise, "EVERY FREQUENCY AT ONCE"),
            )
            segmented(waves.map { SZOption(it.label, hint = it.hint) }, waveform, key = "wave-names")?.let { waveform = it }
            // The scope morphs between waveforms and scrolls while playing.
            val scope = place(SZSize(0, 30))
            fill(scope, theme.background)
            stroke(scope, theme.line)
            fill(SZRect(scope.x + 1, scope.y + scope.height / 2, scope.width - 2, 1), theme.line, SZPatterns.HALF)
            val morph = animatedValue(waveform.toDouble(), key = "morph", easing = 0.18, snap = 0.01)
            val phase = if (playing) time * 1.5 else 0.0
            if (playing) requestFrame()
            fun sample(wave: Int, t: Double): Double {
                val x = t - floor(t)
                return when (wave) {
                    0 -> sin(x * 2 * PI)
                    1 -> 2 * x - 1
                    2 -> if (x < 0.5) 1.0 else -1.0
                    else -> {
                        val n = sin(floor(t * 24) * 12.9898) * 43758.5453
                        (n - floor(n)) * 2 - 1
                    }
                }
            }
            var previous: SZPoint? = null
            val amplitude = (scope.height / 2 - 3).toDouble()
            for (x in 0 until scope.width - 2) {
                val t = x.toDouble() / (scope.width - 2) * 2 + phase
                val lower = floor(morph).toInt()
                val upper = min(lower + 1, 3)
                val f = morph - floor(morph)
                val v = sample(lower, t) * (1 - f) + sample(upper, t) * f
                val p = SZPoint(scope.x + 1 + x, scope.y + scope.height / 2 - (v * amplitude).rnd())
                previous?.let { line(it, p, theme.accentBright) }
                previous = p
            }
            row(height = 11, gap = 3, align = SZAlignment.CENTER) {
                val icon = if (playing) SZIcons.pause else SZIcons.play
                if (iconButton(icon, if (playing) "PAUSE" else "PLAY", on = playing, hint = "RUN THE SCOPE").clicked) playing = !playing
                label(if (playing) "PLAYING" else "PAUSED", theme.textDim)
            }
        }

        card("CLIPPING - HITS RESPECT IT") {
            gap = 4
            val names = listOf("KICK", "SNARE", "HAT", "CLAP", "TOM", "RIM", "COW", "RIDE", "CRASH", "SHAKE")
            val cell = 44
            scrollView(
                SZScrollAxes.HORIZONTAL,
                SZSize(names.size * cell, 13),
                snap = SZSize(cell, 0),
                fade = 16,
                indicators = false,
                key = "drum-strip",
                rect = place(SZSize(0, 13)),
            ) {
                names.forEachIndexed { i, drum ->
                    val r = SZRect(i * cell, 0, cell - 3, 13)
                    if (button(drum, on = i in drums, height = 13, hint = "TOGGLE $drum", key = "drum-$i", rect = r).clicked) drums.toggle(i)
                }
            }
            label("${drums.size} OF ${names.size} ON - DRAG SIDEWAYS", theme.textDim)
        }

        card("LAYERS - AN OVERLAY ON TOP") {
            gap = 4
            val items = listOf(
                SZMenuItem("UNDO", "CMD+Z"),
                SZMenuItem("REDO", "CMD+Y"),
                SZMenuItem("CUT", disabled = true),
                SZMenuItem("SELECT ALL", "CMD+A"),
            )
            row(height = 11, gap = 4, align = SZAlignment.CENTER) {
                if (button("OPEN THE SHEET", on = sheetOpen, hint = "A MODAL PANEL").clicked) sheetOpen = true
                val anchor = place(34, 11)
                if (button("MENU", on = menuOpen, hint = "A POPUP MENU", rect = anchor).clicked) menuOpen = !menuOpen
                label(picked?.let { "PICKED $it" } ?: "NOTHING PICKED", theme.textDim)
                val result = menu(menuOpen, items, anchor, key = "core-menu")
                if (result.dismissed) menuOpen = false
                result.picked?.let {
                    menuOpen = false
                    post("MENU: ${items[it].label}")
                }
            }
        }

        val presets = listOf("BLACK GLASS", "AMBER TUBE", "COLD STEEL", "WARM DUST", "NIGHT SHIFT", "LOW SUN", "STATIC")
        val result = sheet(sheetOpen, "PICK A PRESET", height = 150, key = "preset-sheet") {
            gap = 2
            presets.forEachIndexed { i, preset ->
                row(height = 13, gap = 4, align = SZAlignment.CENTER) {
                    val starOn = i in starred
                    if (iconButton(SZIcons.star, "STAR $preset", on = starOn, size = 13, hint = "FAVORITE", key = "star-$i").clicked) starred.toggle(i)
                    val rest = SZRect(cursor.x + 4, 0, bounds.width - cursor.x - 4, 13)
                    val tap = interaction(rest, key = "preset-$i")
                    val traits = if (picked == preset) SZAccessibilityTraits.BUTTON or SZAccessibilityTraits.SELECTED else SZAccessibilityTraits.BUTTON
                    accessibility(rest, preset, traits = traits, id = tap.id)
                    if (tap.isActive || containsPointer(rest) && ui.pointerKind == SZPointerKind.MOUSE) fill(rest, theme.lineStrong)
                    text(preset, SZPoint(rest.x + 4, 3), theme.font, if (picked == preset) theme.accentBright else theme.text)
                    if (tap.clicked) {
                        picked = preset
                        sheetOpen = false
                        post("PICKED $preset")
                    }
                }
            }
        }
        if (result.dismissed) sheetOpen = false
    }
}
