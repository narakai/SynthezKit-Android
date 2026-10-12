package com.leon.synthez.widgets

import com.leon.synthez.core.SZAccessibilityTraits
import com.leon.synthez.core.SZColor
import com.leon.synthez.core.SZContext
import com.leon.synthez.core.SZEase
import com.leon.synthez.core.SZInteraction
import com.leon.synthez.core.SZPatterns
import com.leon.synthez.core.SZPoint
import com.leon.synthez.core.SZRect
import com.leon.synthez.core.SZSense
import com.leon.synthez.core.SZSize
import com.leon.synthez.core.accessibility
import com.leon.synthez.core.hint
import com.leon.synthez.core.roundToIntAway
import com.leon.synthez.core.roundedAway
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Knob

/**
 * A rotary control: drag up or down (90 px for the full range), wheel to
 * nudge, double-tap to reset. Returns the new value (0..1), or null.
 *
 * With `tapToStep` (needs `steps`), a tap turns it one detent clockwise,
 * wrapping from the last back to the first, like a rotary selector; a
 * double tap then counts as two taps instead of a reset.
 */
fun SZContext.knob(
    title: String,
    value: Double,
    bipolar: Boolean = false,
    steps: Int? = null,
    reset: Double? = null,
    text: String? = null,
    radius: Int = 9,
    tapToStep: Boolean = false,
    hint: String? = null,
    key: Any? = null,
): Double? {
    val theme = theme
    val id = makeKey(key)
    val labelWidth = max(theme.font.measure(title), radius * 2 + 1)
    val frame = place(SZSize(labelWidth, radius * 2 + 1 + 3 + theme.font.glyphHeight * 2 + 3))
    val dial = SZRect(frame.x + (frame.width - (radius * 2 + 1)) / 2, frame.y, radius * 2 + 1, radius * 2 + 1)
    val it = interaction(frame, SZSense.CLICK or SZSense.FOCUS or SZSense.WHEEL or SZSense.DRAG, id)
    accessibility(frame, title, text ?: (value * 100).roundToIntAway().toString(), SZAccessibilityTraits.ADJUSTABLE, id)
    hint(hint, it, frame)

    val start = state(id = id.part("start")) { value }
    // A press that moved more than a few pixels is a drag, not a tap.
    val moved = state(id = id.part("moved")) { false }
    if (it.pressed) {
        start.value = value
        moved.value = false
    }
    if (it.held && abs(it.dy) > 3) moved.value = true
    val stepping = tapToStep && steps != null && steps > 1
    var newValue: Double? = null
    if (it.held && moved.value) newValue = start.value - it.dy / 90.0
    if (it.wheelY != 0.0) newValue = (newValue ?: value) - it.wheelY / 90
    if (it.doubleClicked && !stepping) newValue = reset ?: if (bipolar) 0.5 else 0.0
    if (stepping && it.clicked && !moved.value) {
        val last = steps!! - 1
        val next = (value * last).roundToIntAway() + 1
        newValue = if (next > last) 0.0 else next.toDouble() / last
    }
    newValue?.let { raw ->
        var v = raw
        if (steps != null && steps > 1) v = (v * (steps - 1)).roundedAway() / (steps - 1)
        v = v.coerceIn(0.0, 1.0)
        newValue = if (v == value) null else v
    }
    val shown = animatedValue(newValue ?: value, id.part("angle"), easing = if (it.held) 0.7 else 0.3, snap = 0.002)

    // 270° sweep from bottom-left to bottom-right.
    val center = SZPoint(dial.x + radius, dial.y + radius)
    val ink = if (it.held) theme.accentBright else if (it.isActive) theme.paper else theme.accent
    circle(center, radius, theme.background)
    circle(center, radius, if (it.isActive) theme.textDim else theme.lineStrong, filled = false)
    val from = if (bipolar) 0.5 else 0.0
    val ticks = 28
    for (i in 0..ticks) {
        val t = i.toDouble() / ticks
        val lit = t >= min(from, shown) - 0.001 && t <= max(from, shown) + 0.001
        val angle = (0.75 + t * 1.5) * PI
        val r = radius - 2.0
        pixel(SZPoint(center.x + (cos(angle) * r).roundToIntAway(), center.y + (sin(angle) * r).roundToIntAway()), if (lit) theme.accentDim else theme.line)
    }
    val angle = (0.75 + shown * 1.5) * PI
    val tip = SZPoint(center.x + (cos(angle) * (radius - 2)).roundToIntAway(), center.y + (sin(angle) * (radius - 2)).roundToIntAway())
    line(center, tip, ink)
    circle(center, 1, ink)

    val titleWidth = theme.font.measure(title)
    text(title, SZPoint(frame.x + (frame.width - titleWidth) / 2, dial.maxY + 3), theme.font, theme.textDim)
    val valueText = text ?: ((newValue ?: value) * 100).roundToIntAway().toString()
    val valueWidth = theme.font.measure(valueText)
    text(
        valueText,
        SZPoint(frame.x + (frame.width - valueWidth) / 2, dial.maxY + 3 + theme.font.lineHeight + 1),
        theme.font,
        if (it.isActive) theme.paper else theme.text,
    )
    return newValue
}

// Stepper

/** `‹ value ›`: returns -1 or +1 when an arrow is tapped, 0 otherwise. */
fun SZContext.stepper(
    text: String,
    swatch: SZColor? = null,
    width: Int = 70,
    previousDisabled: Boolean = false,
    nextDisabled: Boolean = false,
    hint: String? = null,
    key: Any? = null,
): Int {
    val theme = theme
    val id = makeKey(key)
    val height = theme.barHeight
    val frame = place(SZSize(width, height))
    fill(frame, theme.background)
    stroke(frame, theme.lineStrong)
    var step = 0
    for ((index, direction) in listOf(-1, 1).withIndex()) {
        val rect = if (direction < 0) SZRect(frame.x, frame.y, height, height) else SZRect(frame.maxX - height, frame.y, height, height)
        val disabled = if (direction < 0) previousDisabled else nextDisabled
        val partID = id.child(10, index)
        var it = interaction(rect, if (disabled) SZSense.NONE else SZSense.BUTTON, partID)
        if (disabled) it = SZInteraction(partID)
        accessibility(
            rect,
            if (direction < 0) "PREVIOUS" else "NEXT",
            traits = if (disabled) SZAccessibilityTraits.BUTTON or SZAccessibilityTraits.NOT_ENABLED else SZAccessibilityTraits.BUTTON,
            id = partID,
        )
        hint(hint, it, frame)
        if (it.clicked) step = direction
        val tone = theme.tone(it, disabled = disabled)
        fill(rect.inset(1), tone.fill)
        val icon = if (direction < 0) SZIcons.chevronLeft else SZIcons.chevronRight
        bitmap(icon, rect.centered(SZSize(icon.width, icon.height)).origin, tone.ink)
    }
    fill(SZRect(frame.x + height, frame.y + 2, 1, height - 4), theme.line)
    fill(SZRect(frame.maxX - height - 1, frame.y + 2, 1, height - 4), theme.line)
    // The value slides in from the side it came from.
    val shift = state(id = id.part("shift")) { 0.0 }
    if (step != 0) shift.value = step * 8.0
    shift.value = SZEase.approach(shift.value, 0.0, dt, easing = 0.3, snap = 0.5)
    if (shift.value != 0.0) requestFrame()
    val middle = SZRect(frame.x + height + 1, frame.y, frame.width - height * 2 - 2, height)
    var textX = middle.x + (middle.width - theme.font.measure(text)) / 2 + shift.value.roundToIntAway()
    if (swatch != null) {
        textX += 4
        fill(SZRect(textX - 8, frame.y + (height - 5) / 2, 5, 5), swatch)
    }
    val font = theme.font
    val paper = theme.paper
    val textY = theme.textTop(frame.y, height)
    clip(middle) {
        text(text, SZPoint(textX, textY), font, paper)
    }
    accessibility(middle, text, traits = SZAccessibilityTraits.STATIC_TEXT, id = id.part("value"))
    return step
}

// Scrub value

enum class SZScrubLook { UNDERLINE, OUTLINE }

/** A number scrubbed by dragging sideways, `dragPixels` per step. */
fun SZContext.scrubValue(
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    step: Double = 1.0,
    text: String? = null,
    look: SZScrubLook = SZScrubLook.UNDERLINE,
    dragPixels: Int = 2,
    width: Int = 40,
    label: String = "VALUE",
    hint: String? = null,
    key: Any? = null,
): Double? {
    val theme = theme
    val id = makeKey(key)
    val frame = place(SZSize(width, theme.controlHeight))
    val it = interaction(frame, SZSense.CLICK or SZSense.FOCUS or SZSense.WHEEL or SZSense.DRAG, id)
    fun format(v: Double) = if (step >= 1) v.toInt().toString() else String.format(Locale.ROOT, "%.2f", v)
    val display = text ?: format(value)
    accessibility(frame, label, display, SZAccessibilityTraits.ADJUSTABLE, id)
    hint(hint, it, frame)
    val start = state(id = id.part("start")) { value }
    if (it.pressed) start.value = value
    var newValue: Double? = null
    if (it.held) newValue = start.value + (it.dx / max(dragPixels, 1)) * step
    if (it.wheelY != 0.0) newValue = (newValue ?: value) - (it.wheelY / 30).roundedAway() * step
    newValue?.let { raw ->
        val v = ((raw / step).roundedAway() * step).coerceIn(range.start, range.endInclusive)
        newValue = if (v == value) null else v
    }
    val shownText = newValue?.let { if (text == null) format(it) else display } ?: display
    val ink = if (it.held) theme.accentBright else if (it.isActive) theme.paper else theme.accent
    when (look) {
        SZScrubLook.UNDERLINE -> fill(SZRect(frame.x, frame.maxY - 1, frame.width, 1), ink, if (it.isActive) SZPatterns.SOLID else SZPatterns.HALF)
        SZScrubLook.OUTLINE -> {
            fill(frame, theme.background)
            stroke(frame, if (it.isActive) theme.text else theme.lineStrong)
        }
    }
    if (it.held) {
        // Arrows show the drag axis.
        val arrowY = frame.y + (frame.height - SZIcons.chevronLeft.height) / 2
        bitmap(SZIcons.chevronLeft, SZPoint(frame.x + 1, arrowY), theme.textDim)
        bitmap(SZIcons.chevronRight, SZPoint(frame.maxX - 5, arrowY), theme.textDim)
    }
    val textWidth = theme.font.measure(shownText)
    text(shownText, SZPoint(frame.x + (frame.width - textWidth) / 2, theme.textTop(frame.y, frame.height)), theme.font, ink)
    return newValue
}
