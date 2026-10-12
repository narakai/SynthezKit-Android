package com.leon.synthez.widgets

import com.leon.synthez.core.SZAccessibilityTraits
import com.leon.synthez.core.SZContext
import com.leon.synthez.core.SZInputEvent
import com.leon.synthez.core.SZKeyCode
import com.leon.synthez.core.SZModifiers
import com.leon.synthez.core.SZPatterns
import com.leon.synthez.core.SZPoint
import com.leon.synthez.core.SZRect
import com.leon.synthez.core.SZSense
import com.leon.synthez.core.SZSize
import com.leon.synthez.core.accessibility
import com.leon.synthez.core.focus
import com.leon.synthez.core.hint
import com.leon.synthez.core.pointer
import com.leon.synthez.core.textEvents
import kotlin.math.max
import kotlin.math.min

data class SZTextFieldResult(
    /** What the field shows: the edit in progress while focused. */
    val text: String,
    val isEditing: Boolean,
    /** The edit changed this frame. */
    val changed: Boolean,
    /** Enter was pressed or focus moved away: adopt [text]. */
    val committed: Boolean,
    /** Escape was pressed: the edit was thrown away. */
    val cancelled: Boolean,
)

private class EditState {
    /** Code points. */
    val text = ArrayList<Int>()
    var original = ""
    var caret = 0
    var scroll = 0
    var wasFocused = false
    var blinkStart = 0.0

    fun string(): String = String(text.toIntArray(), 0, text.size)

    fun set(value: String) {
        text.clear()
        value.codePoints().forEach { text.add(it) }
    }
}

/**
 * A single-line text field. Tap to focus and place the caret; the host's
 * keyboard types into it. Long text scrolls to keep the caret in view.
 *
 * The field edits a copy while focused and reports it in the result: adopt
 * [SZTextFieldResult.text] on `committed` (or on every `changed`).
 *
 * @param allowed Filters typed code points, such as `Character::isDigit`.
 */
fun SZContext.textField(
    value: String,
    placeholder: String = "",
    maxLength: Int? = null,
    allowed: ((Int) -> Boolean)? = null,
    numeric: Boolean = false,
    scale: Int = 1,
    width: Int = 0,
    label: String = "TEXT FIELD",
    hint: String? = null,
    key: Any? = null,
    rect: SZRect? = null,
): SZTextFieldResult {
    val theme = theme
    val font = theme.font
    val id = makeKey(key)
    val height = font.glyphHeight * scale + 6
    val frame = rect ?: place(SZSize(width, height))
    val inner = frame.inset(3, 3, 3, 3)
    val it = interaction(frame, SZSense.CLICK or SZSense.FOCUS, id)
    val state = state(id = id.part("edit")) { EditState() }.value
    hint(hint, it, frame)

    var current = value
    var isEditing = it.focused
    var changed = false
    var committed = false
    var cancelled = false

    fun caretIndex(x: Int): Int {
        var position = 0
        state.text.forEachIndexed { i, cp ->
            val w = font.advance(cp) * scale
            if (x < position + w / 2) return i
            position += w + font.spacing * scale
        }
        return state.text.size
    }

    fun prefixWidth(count: Int): Int {
        if (count == 0) return 0
        var w = 0
        for (i in 0 until count) w += font.advance(state.text[i])
        return (w + (count - 1) * font.spacing) * scale + font.spacing * scale
    }

    if (it.focused) {
        if (!state.wasFocused) {
            state.set(value)
            state.original = value
            state.caret = state.text.size
            state.blinkStart = time
            state.wasFocused = true
        }
        val p = pointer
        if (it.pressed && p != null) {
            state.caret = caretIndex(p.x - inner.x + state.scroll)
            state.blinkStart = time
        }
        val caretRect = SZRect(inner.x + prefixWidth(state.caret) - state.scroll, inner.y, 1, inner.height)
        val before = ArrayList(state.text)
        var finish: Boolean? = null
        for (event in textEvents(it, caretRect, numeric)) {
            when (event) {
                is SZInputEvent.Text -> {
                    val points = event.text.codePoints().toArray()
                    for (cp in points) {
                        if (cp == '\n'.code || cp == '\r'.code) {
                            finish = true
                            break
                        }
                        if (allowed != null && !allowed(cp)) continue
                        if (maxLength != null && state.text.size >= maxLength) break
                        state.text.add(state.caret, cp)
                        state.caret++
                    }
                }
                is SZInputEvent.Key -> {
                    val command = event.modifiers and SZModifiers.COMMAND != 0
                    when (event.code) {
                        SZKeyCode.Backspace -> if (state.caret > 0) {
                            state.text.removeAt(state.caret - 1)
                            state.caret--
                        }
                        SZKeyCode.DeleteForward -> if (state.caret < state.text.size) state.text.removeAt(state.caret)
                        SZKeyCode.Left -> state.caret = if (command) 0 else max(state.caret - 1, 0)
                        SZKeyCode.Right -> state.caret = if (command) state.text.size else min(state.caret + 1, state.text.size)
                        SZKeyCode.Home, SZKeyCode.Up -> state.caret = 0
                        SZKeyCode.End, SZKeyCode.Down -> state.caret = state.text.size
                        SZKeyCode.Enter -> finish = true
                        SZKeyCode.Escape -> finish = false
                        else -> {}
                    }
                }
                else -> {}
            }
            state.blinkStart = time
        }
        if (state.text != before) changed = true
        finish?.let { commit ->
            if (commit) {
                committed = true
            } else {
                state.set(state.original)
                cancelled = true
                changed = true
            }
            state.wasFocused = false
            focus(null)
            isEditing = false
        }
        current = state.string()
    } else if (state.wasFocused) {
        // Focus moved elsewhere: keep what was typed.
        state.wasFocused = false
        committed = true
        current = state.string()
    }

    // Keep the caret in view.
    val textWidth = font.measure(current) * scale
    if (isEditing) {
        val caretX = prefixWidth(state.caret)
        if (caretX - state.scroll > inner.width - 2) state.scroll = caretX - inner.width + 2
        if (caretX - state.scroll < 0) state.scroll = caretX
        state.scroll = max(min(state.scroll, max(textWidth - inner.width + 2, 0)), 0)
    } else {
        state.scroll = 0
    }

    val focusGlow = animatedValue(if (isEditing) 1.0 else 0.0, id.part("glow"), easing = 0.3, snap = 0.05)
    fill(frame, theme.background)
    stroke(frame, if (focusGlow > 0.5) theme.accent else if (it.isActive) theme.text else theme.lineStrong)
    if (focusGlow > 0) fill(SZRect(frame.x, frame.maxY, frame.width, 1), theme.accentDim, SZPatterns.shade(focusGlow))
    accessibility(frame, label, current.ifEmpty { placeholder }, SZAccessibilityTraits.TEXT_FIELD, id)
    val shownText = current
    val editing = isEditing
    clip(inner.inset(-1, 0, -1, 0)) {
        if (shownText.isEmpty()) {
            text(placeholder, SZPoint(inner.x, inner.y), font, theme.textDim, scale)
        } else {
            text(shownText, SZPoint(inner.x - state.scroll, inner.y), font, if (editing) theme.paper else theme.text, scale)
        }
        if (editing) {
            // Blink: solid right after typing, then 0.5 s on / 0.5 s off.
            val phase = (time - state.blinkStart) % 1.0
            if (phase < 0.5) {
                val caretX = inner.x + prefixWidth(state.caret) - state.scroll - if (state.caret > 0) 1 else 0
                fill(SZRect(caretX, inner.y - 1, max(scale, 1), inner.height + 2), theme.accentBright)
            }
            requestFrame(time + if (phase < 0.5) 0.5 - phase else 1.0 - phase)
        }
    }
    if (isEditing && textWidth > inner.width) {
        // Edge marks show there is more text.
        if (state.scroll > 0) fill(SZRect(frame.x + 1, inner.y, 1, inner.height), theme.accent)
        if (textWidth - state.scroll > inner.width) fill(SZRect(frame.maxX - 2, inner.y, 1, inner.height), theme.accent)
    }
    return SZTextFieldResult(current, isEditing, changed, committed, cancelled)
}
