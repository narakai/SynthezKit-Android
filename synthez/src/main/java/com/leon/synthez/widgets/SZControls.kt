package com.leon.synthez.widgets

import com.leon.synthez.core.SZAccessibilityTraits
import com.leon.synthez.core.SZAlignment
import com.leon.synthez.core.SZAxis
import com.leon.synthez.core.SZBitmap
import com.leon.synthez.core.SZColor
import com.leon.synthez.core.SZContext
import com.leon.synthez.core.SZID
import com.leon.synthez.core.SZInteraction
import com.leon.synthez.core.SZPatterns
import com.leon.synthez.core.SZPoint
import com.leon.synthez.core.SZRect
import com.leon.synthez.core.SZSense
import com.leon.synthez.core.SZSize
import com.leon.synthez.core.accessibility
import com.leon.synthez.core.hint
import com.leon.synthez.core.layoutText
import com.leon.synthez.core.pointer
import com.leon.synthez.core.roundToIntAway
import com.leon.synthez.core.roundedAway
import com.leon.synthez.core.text
import kotlin.math.abs
import kotlin.math.max

// Widgets are extension functions on SZContext. Each returns what happened
// this frame; none keeps callbacks. Without a `rect`, a widget places itself
// at its natural size in the region's flow. Every interactive widget
// describes itself for accessibility and shows its `hint` in the status line
// and tooltip while touched.

// Label

/** One line of text at its natural size. */
fun SZContext.label(text: String, color: SZColor? = null, scale: Int = 1, rect: SZRect? = null): SZRect {
    val theme = theme
    val font = theme.font
    val size = SZSize(font.measure(text) * scale, font.glyphHeight * scale)
    val frame = rect ?: place(size)
    val origin = frame.centered(size).origin
    text(text, SZPoint(if (rect == null) frame.x else origin.x, origin.y), font, color ?: theme.text, scale)
    return frame
}

/** Wrapped text as wide as the region (or `width`). */
fun SZContext.paragraph(
    text: String,
    color: SZColor? = null,
    scale: Int = 1,
    width: Int? = null,
    align: SZAlignment = SZAlignment.START,
): SZRect {
    val theme = theme
    val w = width ?: bounds.width
    val layout = layoutText(text, theme.font, w, scale)
    val frame = place(SZSize(w, max(layout.height - scale, 0)))
    text(layout, frame.origin, theme.font, color ?: theme.text, scale, align, w)
    return frame
}

// Buttons

private fun SZContext.pressable(id: SZID, frame: SZRect, disabled: Boolean, label: String, on: Boolean, hint: String?): SZInteraction {
    var it = interaction(frame, if (disabled) SZSense.NONE else SZSense.BUTTON, id)
    if (disabled) it = SZInteraction(id)
    var traits = SZAccessibilityTraits.BUTTON
    if (on) traits = traits or SZAccessibilityTraits.SELECTED
    if (disabled) traits = traits or SZAccessibilityTraits.NOT_ENABLED
    accessibility(frame, label, traits = traits, id = id)
    hint(hint, it, frame)
    return it
}

/** A bordered text button. */
fun SZContext.button(
    title: String,
    on: Boolean = false,
    disabled: Boolean = false,
    height: Int? = null,
    padding: Int = 4,
    hint: String? = null,
    key: Any? = null,
    rect: SZRect? = null,
): SZInteraction {
    val theme = theme
    val id = makeKey(key)
    val textWidth = theme.font.measure(title)
    val frame = rect ?: place(SZSize(textWidth + padding * 2 + 2, height ?: theme.controlHeight))
    val it = pressable(id, frame, disabled, title, on, hint)
    val tone = theme.tone(it, on, disabled)
    fill(frame, tone.fill)
    stroke(frame, tone.border)
    val textOrigin = frame.centered(SZSize(textWidth, theme.font.glyphHeight)).origin
    text(title, SZPoint(textOrigin.x, textOrigin.y + if (it.held) 1 else 0), theme.font, tone.ink)
    return it
}

/** A square button showing a 1-bit icon. */
fun SZContext.iconButton(
    icon: SZBitmap,
    label: String,
    on: Boolean = false,
    disabled: Boolean = false,
    size: Int? = null,
    hint: String? = null,
    key: Any? = null,
    rect: SZRect? = null,
): SZInteraction {
    val theme = theme
    val id = makeKey(key)
    val side = size ?: theme.controlHeight
    val frame = rect ?: place(SZSize(side, side))
    val it = pressable(id, frame, disabled, label, on, hint)
    val tone = theme.tone(it, on, disabled)
    fill(frame, tone.fill)
    stroke(frame, tone.border)
    val origin = frame.centered(SZSize(icon.width, icon.height)).origin
    bitmap(icon, SZPoint(origin.x, origin.y + if (it.held) 1 else 0), tone.ink)
    return it
}

/** Text that reacts like a link: no frame, underline while touched. */
fun SZContext.textButton(
    title: String,
    color: SZColor? = null,
    disabled: Boolean = false,
    hint: String? = null,
    key: Any? = null,
    rect: SZRect? = null,
): SZInteraction {
    val theme = theme
    val id = makeKey(key)
    val size = SZSize(theme.font.measure(title), theme.font.glyphHeight + 2)
    val frame = rect ?: place(size)
    val it = pressable(id, frame, disabled, title, false, hint)
    val ink = if (disabled) theme.textDim else if (it.held) theme.accentBright else color ?: theme.accent
    val origin = frame.centered(size).origin
    text(title, origin, theme.font, ink)
    val underline = SZRect(origin.x, origin.y + size.height - 1, size.width, 1)
    fill(underline, ink, if (it.isActive) SZPatterns.SOLID else SZPatterns.HALF)
    return it
}

// Checkbox

/** A box and a label. Returns the interaction; toggle on `clicked`. */
fun SZContext.checkbox(
    title: String,
    checked: Boolean,
    disabled: Boolean = false,
    hint: String? = null,
    key: Any? = null,
    rect: SZRect? = null,
): SZInteraction {
    val theme = theme
    val id = makeKey(key)
    val box = 9
    val width = box + 4 + theme.font.measure(title)
    val frame = rect ?: place(SZSize(width, max(box, theme.font.glyphHeight)))
    val it = pressable(id, frame, disabled, title, checked, hint)
    val boxRect = SZRect(frame.x, frame.y + (frame.height - box) / 2, box, box)
    val tone = theme.tone(it, disabled = disabled)
    fill(boxRect, theme.background)
    stroke(boxRect, if (it.isActive) theme.text else tone.border)
    // The check grows in and out.
    val grown = animatedValue(if (checked) 1.0 else 0.0, id.part("check"), easing = 0.35, snap = 0.05)
    val inset = 2 + ((1 - grown) * 2.5).roundToIntAway()
    if (grown > 0 && inset < 5) fill(boxRect.inset(inset), if (disabled) theme.textDim else theme.accent)
    text(title, SZPoint(boxRect.maxX + 4, theme.textTop(frame.y, frame.height)), theme.font, if (disabled) theme.textDim else if (it.isActive) theme.paper else theme.text)
    return it
}

// Segmented

/** An option with an optional icon. */
data class SZOption(val label: String, val icon: SZBitmap? = null, val hint: String? = null)

/** Plain text options. */
fun szOptions(vararg labels: String): List<SZOption> = labels.map { SZOption(it) }

/** Equal-width segments with a light that glides to the selection. Returns the newly picked index, or null. */
fun SZContext.segmented(
    options: List<SZOption>,
    selected: Int,
    height: Int? = null,
    key: Any? = null,
    rect: SZRect? = null,
): Int? {
    if (options.isEmpty()) return null
    val theme = theme
    val id = makeKey(key)
    val frame = rect ?: place(SZSize(0, height ?: theme.barHeight))
    val inner = frame.inset(1)
    val segment = max(inner.width / options.size, 1)
    fill(frame, theme.background)
    stroke(frame, theme.lineStrong)

    val sel = selected.coerceIn(0, options.size - 1)
    val lightX = animatedValue((sel * segment).toDouble(), id.part("light"), easing = 0.25, snap = 0.5)
    // The last cell takes the remainder; the light stretches into it.
    val lightLeft = lightX.roundToIntAway()
    val lastStart = (options.size - 1) * segment
    val stretch = max(0, lightLeft + segment - lastStart) * (inner.width - options.size * segment) / max(segment, 1)
    fill(SZRect(inner.x + lightLeft, inner.y, segment + stretch, inner.height), theme.accent)

    var picked: Int? = null
    options.forEachIndexed { i, option ->
        val cellWidth = if (i == options.size - 1) inner.maxX - (inner.x + i * segment) else segment
        val cell = SZRect(inner.x + i * segment, inner.y, cellWidth, inner.height)
        val cellID = id.child(7, i)
        val it = interaction(cell, id = cellID)
        accessibility(
            cell,
            option.label,
            traits = if (i == sel) SZAccessibilityTraits.BUTTON or SZAccessibilityTraits.SELECTED else SZAccessibilityTraits.BUTTON,
            id = cellID,
        )
        hint(option.hint, it, cell)
        if (it.clicked && i != sel) picked = i
        // Cells the light covers read in background ink.
        val covered = abs(i * segment - lightX) < segment / 2.0
        val ink = if (covered) theme.background else if (it.isActive) theme.paper else theme.textDim
        val icon = option.icon
        if (icon != null) {
            bitmap(icon, cell.centered(SZSize(icon.width, icon.height)).origin, ink)
        } else {
            val textSize = SZSize(theme.font.measure(option.label), theme.font.glyphHeight)
            text(option.label, cell.centered(textSize).origin, theme.font, ink)
        }
    }
    return picked
}

// Chips

/** Chips that wrap onto new lines, each toggled independently. Returns the index tapped this frame. */
fun SZContext.chipGroup(
    options: List<SZOption>,
    selected: Set<Int>,
    height: Int? = null,
    gap: Int = 3,
    padding: Int = 4,
    key: Any? = null,
): Int? {
    val theme = theme
    val id = makeKey(key)
    val chipHeight = height ?: theme.controlHeight
    val width = bounds.width
    var x = 0
    var y = 0
    val frames = ArrayList<SZRect>(options.size)
    for (option in options) {
        val w = option.icon?.let { it.width + padding * 2 + 2 } ?: (theme.font.measure(option.label) + padding * 2 + 2)
        if (x > 0 && x + w > width) {
            x = 0
            y += chipHeight + gap
        }
        frames.add(SZRect(x, y, w, chipHeight))
        x += w + gap
    }
    val area = place(SZSize(width, y + chipHeight))
    var tapped: Int? = null
    options.forEachIndexed { i, option ->
        val frame = frames[i].offsetBy(area.x, area.y)
        val on = i in selected
        val chipID = id.child(9, i)
        val it = interaction(frame, id = chipID)
        accessibility(
            frame,
            option.label,
            traits = if (on) SZAccessibilityTraits.BUTTON or SZAccessibilityTraits.SELECTED else SZAccessibilityTraits.BUTTON,
            id = chipID,
        )
        hint(option.hint, it, frame)
        if (it.clicked) tapped = i
        val tone = theme.tone(it, on)
        fill(frame, tone.fill)
        stroke(frame, tone.border)
        val icon = option.icon
        if (icon != null) {
            bitmap(icon, frame.centered(SZSize(icon.width, icon.height)).origin, tone.ink)
        } else {
            val size = SZSize(theme.font.measure(option.label), theme.font.glyphHeight)
            text(option.label, frame.centered(size).origin, theme.font, tone.ink)
        }
    }
    return tapped
}

// Slider

/** A horizontal or vertical slider. Returns the new value while dragging, or null. Double-tap returns `reset`. */
fun SZContext.slider(
    value: Double,
    range: ClosedFloatingPointRange<Double> = 0.0..1.0,
    step: Double? = null,
    axis: SZAxis = SZAxis.HORIZONTAL,
    bipolar: Boolean = false,
    reset: Double? = null,
    length: Int = 0,
    thickness: Int = 9,
    label: String = "SLIDER",
    hint: String? = null,
    key: Any? = null,
    rect: SZRect? = null,
): Double? {
    val theme = theme
    val id = makeKey(key)
    val horizontal = axis == SZAxis.HORIZONTAL
    val frame = rect ?: place(if (horizontal) SZSize(length, thickness) else SZSize(thickness, if (length == 0) 48 else length))
    val it = interaction(frame, SZSense.CLICK or SZSense.FOCUS or SZSense.WHEEL or SZSense.DRAG, id)
    val lower = range.start
    val span = max(range.endInclusive - lower, Math.ulp(1.0))
    val travel = max((if (horizontal) frame.width else frame.height) - 3, 1)
    val percent = ((value - lower) / span * 100).roundToIntAway()
    accessibility(frame, label, "$percent%", SZAccessibilityTraits.ADJUSTABLE, id)
    hint(hint, it, frame)

    // A press jumps the thumb under the finger, then drags from there.
    val dragStart = state(id.part("start")) { value }
    val p = pointer
    if (it.pressed && p != null) {
        val along = if (horizontal) (p.x - frame.x - 1).toDouble() else (frame.maxY - 2 - p.y).toDouble()
        dragStart.value = lower + (along / travel).coerceIn(0.0, 1.0) * span
    }

    var newValue: Double? = null
    if (it.held || it.pressed) {
        val delta = if (horizontal) it.dx.toDouble() else -it.dy.toDouble()
        newValue = dragStart.value + delta / travel * span
    }
    val wheel = if (horizontal) it.wheelX else -it.wheelY
    if (wheel != 0.0) newValue = (newValue ?: value) + wheel / travel * span
    if (it.doubleClicked && reset != null) newValue = reset
    newValue?.let { raw ->
        var v = raw
        if (step != null && step > 0) v = ((v - lower) / step).roundedAway() * step + lower
        v = v.coerceIn(lower, range.endInclusive)
        newValue = if (v == value) null else v
    }

    val shown = newValue ?: value
    val t = (shown - lower) / span
    // The thumb glides a little, so steps and jumps read as motion.
    val pos = animatedValue(t * travel, id.part("thumb"), easing = if (it.held) 0.6 else 0.3, snap = 0.5).roundToIntAway()
    val zero = if (bipolar) ((0 - lower) / span * travel).roundToIntAway() else 0
    val thumb = if (it.held) theme.accentBright else if (it.isActive) theme.paper else theme.accent
    fill(frame, theme.background)
    stroke(frame, if (it.isActive) theme.textDim else theme.line)
    val a = minOf(zero, pos)
    val b = maxOf(zero, pos)
    if (horizontal) {
        fill(SZRect(frame.x + 1 + a, frame.y + 2, b - a + 1, frame.height - 4), theme.accentDim)
        if (bipolar) fill(SZRect(frame.x + 1 + zero, frame.y + 1, 1, frame.height - 2), theme.textDim)
        fill(SZRect(frame.x + pos, frame.y, 3, frame.height), thumb)
    } else {
        fill(SZRect(frame.x + 2, frame.maxY - 2 - b, frame.width - 4, b - a + 1), theme.accentDim)
        if (bipolar) fill(SZRect(frame.x + 1, frame.maxY - 2 - zero, frame.width - 2, 1), theme.textDim)
        fill(SZRect(frame.x, frame.maxY - 3 - pos, frame.width, 3), thumb)
    }
    return newValue
}

// Card

/** A titled panel. `body` lays out inside its padding; the card is as tall as `height`, or as its content when null. */
fun SZContext.card(
    title: String,
    height: Int? = null,
    padding: Int = 5,
    key: Any? = null,
    body: SZContext.() -> Unit,
) {
    val theme = theme
    val titleHeight = theme.font.glyphHeight + 4
    // The panel is drawn before its content, so without a fixed height it
    // uses the height the content measured last frame.
    val id = makeKey(key)
    val measured = state(id = id.part("height")) { height ?: (titleHeight + padding * 2) }
    val frameHeight = height ?: measured.value
    val frame = place(SZSize(0, frameHeight))
    fill(frame, theme.panel)
    stroke(frame, theme.line)
    fill(SZRect(frame.x + 1, frame.y + 1, frame.width - 2, titleHeight - 1), theme.background)
    fill(SZRect(frame.x, frame.y + titleHeight - 1, frame.width, 1), theme.line)
    fill(SZRect(frame.x + 1, frame.y + 1, 2, titleHeight - 2), theme.accentDim)
    text(title, SZPoint(frame.x + padding, frame.y + 2), theme.font, theme.textDim)
    accessibility(SZRect(frame.x, frame.y, frame.width, titleHeight), title, traits = SZAccessibilityTraits.HEADER, id = id.part("title"))
    val content = SZRect(frame.x + padding, frame.y + titleHeight + padding, frame.width - padding * 2, max(frameHeight - titleHeight - padding * 2, 0))
    val bodyRect = SZRect(content.x, content.y, content.width, if (height == null) Short.MAX_VALUE.toInt() else content.height)
    val used = region(bodyRect, key = id.part("body"), reserve = false) {
        body()
        reach.y
    }
    if (height == null) {
        val natural = titleHeight + padding * 2 + used
        if (natural != measured.value) {
            measured.value = natural
            requestFrame()
        }
    }
}
