package com.leon.synthez.core

enum class SZEdge { TOP, BOTTOM, LEFT, RIGHT }

// Pointer

val SZContext.isPointerDown: Boolean get() = ui.isPointerDown

/** The pointer in local coordinates. */
val SZContext.pointer: SZPoint? get() = ui.pointer?.let { it - origin }

/** Where the current press started, in local coordinates. */
val SZContext.pressOrigin: SZPoint get() = ui.pressOrigin - origin

/** Virtual pixels per second; after a release, the fling velocity. */
val SZContext.pointerVelocity: SZVector get() = ui.pointerVelocity

/** Whether `id` may take the pointer from the control holding it. */
fun SZContext.canCapturePointer(id: SZID): Boolean = ui.canCapture(id)

/**
 * Takes the pointer: from now on `id` is held and the control that was
 * pressed will not click. Scroll views call this once a drag passes their slop.
 */
fun SZContext.capturePointer(id: SZID) = ui.capture(id)

fun SZContext.focus(id: SZID?) = ui.moveFocus(id)

// Keyboard

/** Takes a key press no one consumed yet. */
fun SZContext.takeKey(key: SZKeyCode, modifiers: Int = SZModifiers.NONE): Boolean = ui.takeKey(key, modifiers)

/**
 * For a focused text widget: claims the keyboard, tells the host where the
 * caret is (local), and takes this frame's key and text events.
 */
fun SZContext.textEvents(interaction: SZInteraction, caret: SZRect, numeric: Boolean = false): List<SZInputEvent> {
    if (!interaction.focused) return emptyList()
    return ui.takeTextEvents(absolute(caret), numeric)
}

// Hints and accessibility

/** Shows `text` in the status line and tooltip while `interaction` is hovered or held. */
fun SZContext.hint(text: String?, interaction: SZInteraction, rect: SZRect) {
    if (text == null || !(interaction.hovered || interaction.held)) return
    ui.setHint(text, interaction.id, absolute(rect).intersection(clipRect))
}

/** Describes `rect` (local) to screen readers and UI tests. */
fun SZContext.accessibility(rect: SZRect, label: String, value: String? = null, traits: Int = SZAccessibilityTraits.BUTTON, id: SZID) {
    val abs = absolute(rect).intersection(clipRect)
    if (abs.isEmpty) return
    ui.addAccessibility(SZAccessibilityItem(id, abs, layer, label, value, traits))
}

/** Hides everything below this layer from screen readers this frame. */
fun SZContext.markModal() = ui.raiseModalLayer(layer)

// Filters

/** Moves what is drawn in `rect` (local) toward `target` by `field` (local coordinates). */
fun SZContext.filter(rect: SZRect, field: SZStrengthField, target: SZColor, blend: SZFilterBlend = SZFilterBlend.DITHER) {
    val abs = absolute(rect)
    if (abs.isEmpty || abs.intersection(clipRect).isEmpty) return
    if (field is SZStrengthField.Constant && field.strength <= 0) return
    drawList.append(SZDrawOp.Filter(abs, field.offsetBy(origin.x, origin.y), ui.palette.filterMaps(target), blend), clipIndex, layer)
}

/** A field from 0 at the far side of `rect` to `strength` at `edge`. */
fun SZContext.ramp(rect: SZRect, edge: SZEdge, strength: Double = 1.0): SZStrengthField = when (edge) {
    SZEdge.TOP -> SZStrengthField.Linear(SZPoint(rect.x, rect.maxY), SZPoint(rect.x, rect.y), 0.0, strength)
    SZEdge.BOTTOM -> SZStrengthField.Linear(SZPoint(rect.x, rect.y), SZPoint(rect.x, rect.maxY), 0.0, strength)
    SZEdge.LEFT -> SZStrengthField.Linear(SZPoint(rect.maxX, rect.y), SZPoint(rect.x, rect.y), 0.0, strength)
    SZEdge.RIGHT -> SZStrengthField.Linear(SZPoint(rect.x, rect.y), SZPoint(rect.maxX, rect.y), 0.0, strength)
}

// Text

/** Wrapped text, laid out once and reused while it stays the same. */
fun SZContext.layoutText(text: String, font: SZBitmapFont, width: Int, scale: Int = 1, wrap: SZTextWrap = SZTextWrap.WORD): SZTextLayout =
    ui.textLayouts.layout(text, font, width, scale, wrap)

/** Draws `layout` with its top-left at `at`. */
fun SZContext.text(
    layout: SZTextLayout,
    at: SZPoint,
    font: SZBitmapFont,
    color: SZColor,
    scale: Int = 1,
    align: SZAlignment = SZAlignment.START,
    width: Int? = null,
) {
    var y = at.y
    val w = width ?: layout.width
    for (line in layout.lines) {
        val lineWidth = if (align == SZAlignment.START) 0 else font.measure(line) * scale
        val x = when (align) {
            SZAlignment.START, SZAlignment.STRETCH -> at.x
            SZAlignment.CENTER -> at.x + (w - lineWidth) / 2
            SZAlignment.END -> at.x + w - lineWidth
        }
        text(line, SZPoint(x, y), font, color, scale)
        y += layout.lineHeight
    }
}
