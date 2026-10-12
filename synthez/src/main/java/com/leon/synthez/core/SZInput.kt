package com.leon.synthez.core

/** What produced pointer events. Touches have no hover: a touch pointer only exists while a finger is down. */
enum class SZPointerKind { TOUCH, MOUSE }

/** Keys widgets react to. Printable characters arrive as [SZInputEvent.Text] instead. */
sealed class SZKeyCode {
    data object Backspace : SZKeyCode()
    data object DeleteForward : SZKeyCode()
    data object Enter : SZKeyCode()
    data object Escape : SZKeyCode()
    data object Tab : SZKeyCode()
    data object Left : SZKeyCode()
    data object Right : SZKeyCode()
    data object Up : SZKeyCode()
    data object Down : SZKeyCode()
    data object Home : SZKeyCode()
    data object End : SZKeyCode()

    /** A character pressed with a command modifier, lowercased: Ctrl+Z. */
    data class Character(val codePoint: Int) : SZKeyCode()
}

/** Modifier key flags. */
object SZModifiers {
    const val NONE = 0
    const val SHIFT = 1 shl 0
    const val CONTROL = 1 shl 1
    const val OPTION = 1 shl 2
    const val COMMAND = 1 shl 3
}

/** One input event, in virtual pixels. */
sealed class SZInputEvent {
    data class PointerDown(val point: SZPoint, val kind: SZPointerKind) : SZInputEvent()
    data class PointerMove(val point: SZPoint, val kind: SZPointerKind) : SZInputEvent()
    data class PointerUp(val point: SZPoint, val kind: SZPointerKind) : SZInputEvent()

    /** The system took the touch away (a gesture, a dialog): no click. */
    data object PointerCancel : SZInputEvent()

    /** The pointer left the canvas (mouse hover). */
    data object PointerExit : SZInputEvent()

    /** Scroll-wheel deltas in virtual pixels, positive is right/down. */
    data class Wheel(val point: SZPoint, val dx: Double, val dy: Double) : SZInputEvent()

    data class Key(val code: SZKeyCode, val modifiers: Int = SZModifiers.NONE) : SZInputEvent()

    /** Text typed or pasted, delivered to the focused text widget. */
    data class Text(val text: String) : SZInputEvent()
}

/** The senses an interactive rectangle answers to, as flags. */
object SZSense {
    const val NONE = 0
    const val CLICK = 1 shl 0
    const val WHEEL = 1 shl 1
    const val FOCUS = 1 shl 2

    /** Hover only: the rectangle blocks hover beneath it but takes nothing. */
    const val HOVER = 1 shl 3

    /** Keeps its drags: a scroll view underneath will not take the pointer over once it moves. Sliders and knobs use it. */
    const val DRAG = 1 shl 4

    const val BUTTON = CLICK or FOCUS
}

/**
 * What happened to one interactive rectangle this frame.
 *
 * Routing uses the previous frame's rectangles, so a control learns about its
 * input in the same call that draws it.
 */
class SZInteraction(val id: SZID) {
    /** The pointer is over it and nothing above takes it. With touches, true only while the finger that pressed it stays inside. */
    var hovered = false

    /** Went down on it this frame. */
    var pressed = false

    /** Is being held: it was pressed and has not been released. */
    var held = false

    /** Was let go this frame, inside or not. */
    var released = false

    /** Was let go inside this frame after a press on it. */
    var clicked = false

    /** Presses in quick succession, counting this one: 2 is a double tap. */
    var presses = 0
    var focused = false

    /** Pointer movement since the press (or since it captured the pointer), while held. */
    var dx = 0
    var dy = 0
    var wheelX = 0.0
    var wheelY = 0.0

    /** Seconds it has been held. */
    var heldDuration = 0.0

    val doubleClicked: Boolean get() = pressed && presses == 2
    val isActive: Boolean get() = held || hovered
}

/** A rectangle registered for input, kept to route the next frame. */
internal data class SZHit(val id: SZID, val rect: SZRect, val layer: Int, val sense: Int)

/** Where a focused text widget wants the host's keyboard. */
data class SZTextInputRequest(
    /** The caret, absolute virtual pixels, for placing IME candidates. */
    val caret: SZRect,
    val numeric: Boolean,
)

/** Traits of an accessibility item, as flags. */
object SZAccessibilityTraits {
    const val NONE = 0
    const val BUTTON = 1 shl 0
    const val SELECTED = 1 shl 1
    const val ADJUSTABLE = 1 shl 2
    const val STATIC_TEXT = 1 shl 3
    const val NOT_ENABLED = 1 shl 4
    const val HEADER = 1 shl 5
    const val TEXT_FIELD = 1 shl 6
    const val TAB = 1 shl 7
}

/** One element a screen reader or UI test can find, collected each frame. */
data class SZAccessibilityItem(
    val id: SZID,
    /** Absolute virtual pixels, clipped. */
    val rect: SZRect,
    val layer: Int,
    val label: String,
    val value: String?,
    val traits: Int,
)
