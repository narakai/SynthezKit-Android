package com.leon.synthez.widgets

import com.leon.synthez.core.SZAccessibilityTraits
import com.leon.synthez.core.SZContext
import com.leon.synthez.core.SZInteraction
import com.leon.synthez.core.SZKeyCode
import com.leon.synthez.core.SZPatterns
import com.leon.synthez.core.SZPoint
import com.leon.synthez.core.SZRect
import com.leon.synthez.core.SZSense
import com.leon.synthez.core.SZSize
import com.leon.synthez.core.accessibility
import com.leon.synthez.core.hint
import com.leon.synthez.core.layoutText
import com.leon.synthez.core.markModal
import com.leon.synthez.core.roundToIntAway
import com.leon.synthez.core.takeKey
import com.leon.synthez.core.text
import kotlin.math.max
import kotlin.math.min

// Tabs

/** A tab bar whose highlight slides and stretches to the selected tab. Returns the tab tapped this frame. */
fun SZContext.tabs(
    titles: List<String>,
    selected: Int,
    height: Int? = null,
    gap: Int = 2,
    key: Any? = null,
    rect: SZRect? = null,
): Int? {
    val theme = theme
    val id = makeKey(key)
    val tabHeight = height ?: theme.barHeight
    val frame = rect ?: place(SZSize(0, tabHeight))
    // Spread the spare width evenly over the tabs.
    val natural = titles.map { theme.font.measure(it) + 8 }
    val count = max(titles.size, 1)
    val spare = max(frame.width - natural.sum() - gap * max(titles.size - 1, 0), 0)
    var x = frame.x
    val cells = ArrayList<SZRect>(titles.size)
    natural.forEachIndexed { i, width ->
        val extra = spare / count + if (i < spare % count) 1 else 0
        cells.add(SZRect(x, frame.y, width + extra, tabHeight))
        x += width + extra + gap
    }
    fill(SZRect(frame.x, frame.maxY - 1, frame.width, 1), theme.lineStrong)
    if (cells.isEmpty()) return null
    val target = cells[selected.coerceIn(0, cells.size - 1)]
    val lx = animatedValue(target.x.toDouble(), id.part("x"), easing = 0.3, snap = 0.5)
    val lw = animatedValue(target.width.toDouble(), id.part("w"), easing = 0.3, snap = 0.5)
    val light = SZRect(lx.roundToIntAway(), frame.y, lw.roundToIntAway(), tabHeight)
    fill(light, theme.accent)

    var tapped: Int? = null
    cells.forEachIndexed { i, cell ->
        val tabID = id.child(11, i)
        val it = interaction(cell, id = tabID)
        val tabTraits = SZAccessibilityTraits.TAB or SZAccessibilityTraits.BUTTON
        accessibility(cell, titles[i], traits = if (i == selected) tabTraits or SZAccessibilityTraits.SELECTED else tabTraits, id = tabID)
        if (it.clicked && i != selected) tapped = i
        val covered = light.intersection(cell).width * 2 > cell.width
        if (!covered && it.isActive) fill(SZRect(cell.x, cell.y, cell.width, cell.height - 1), theme.panel)
        val ink = if (covered) theme.background else if (it.isActive) theme.paper else theme.textDim
        val size = SZSize(theme.font.measure(titles[i]), theme.font.glyphHeight)
        text(titles[i], cell.centered(size).origin, theme.font, ink)
    }
    return tapped
}

// Sheet

data class SZSheetResult(
    /** Tapped outside or on the close button. */
    val dismissed: Boolean,
    /** 0 closed .. 1 fully open. */
    val shown: Double,
)

/** A modal panel that rises from the bottom. The screen behind it pixelates and darkens as it opens. */
fun SZContext.sheet(
    isPresented: Boolean,
    title: String,
    height: Int = 160,
    key: Any? = null,
    body: SZContext.() -> Unit,
): SZSheetResult {
    val theme = theme
    val id = makeKey(key)
    val shown = animatedValue(if (isPresented) 1.0 else 0.0, id.part("shown"), easing = 0.16, snap = 0.004)
    if (shown <= 0) return SZSheetResult(false, 0.0)
    var dismissed = false
    overlay(key = id.part("overlay")) {
        val screen = SZRect(0, 0, ui.screen.width, ui.screen.height).offsetBy(-origin.x, -origin.y)
        val mosaicSize = (shown * 4).roundToIntAway()
        if (mosaicSize >= 2) mosaic(screen, mosaicSize)
        remap(screen, palette.mixMap(theme.background, shown * 0.8))
        if (shown > 0.5) markModal()

        val backdrop = interaction(screen, SZSense.CLICK or SZSense.WHEEL, id.part("backdrop"))
        if (backdrop.clicked && isPresented) dismissed = true

        // `shown` already eases out.
        val width = min(screen.width - 16, 420)
        val panel = SZRect(screen.x + (screen.width - width) / 2, screen.maxY - ((height + 8) * shown).toInt(), width, height)
        interaction(panel, SZSense.CLICK or SZSense.WHEEL, id.part("panel"))
        fill(panel, theme.panel)
        stroke(panel, theme.textDim)
        val header = theme.font.glyphHeight + 9
        val closeSize = theme.barHeight
        fill(SZRect(panel.x + 1, panel.y + 1, panel.width - 2, header - 1), theme.background)
        fill(SZRect(panel.x, panel.y + header, panel.width, 1), theme.line)
        text(title, SZPoint(panel.x + 6, theme.textTop(panel.y + 1, header - 1)), theme.font, theme.paper)
        accessibility(SZRect(panel.x, panel.y, panel.width - closeSize - 3, header), title, traits = SZAccessibilityTraits.HEADER, id = id.part("title"))
        val close = SZRect(panel.maxX - closeSize - 2, panel.y + 2, closeSize, closeSize)
        if (iconButton(SZIcons.close, "CLOSE", key = id.part("close"), rect = close).clicked) dismissed = true
        region(SZRect(panel.x + 6, panel.y + header + 6, panel.width - 12, panel.height - header - 12), clip = true, key = id.part("body")) {
            body()
        }
    }
    return SZSheetResult(dismissed, shown)
}

// Menu

data class SZMenuItem(
    val label: String,
    val shortcut: String? = null,
    val disabled: Boolean = false,
    val hint: String? = null,
)

data class SZMenuResult(val picked: Int?, val dismissed: Boolean)

/**
 * A popup list anchored to `anchor` (local). It unrolls downward when opened
 * and rolls back up when closed; tapping outside dismisses it.
 */
fun SZContext.menu(
    isPresented: Boolean,
    items: List<SZMenuItem>,
    anchor: SZRect,
    selected: Int? = null,
    width: Int? = null,
    key: Any? = null,
): SZMenuResult {
    val theme = theme
    val id = makeKey(key)
    val reveal = animatedValue(if (isPresented) 1.0 else 0.0, id.part("reveal"), easing = 0.3, snap = 0.01)
    if (reveal <= 0) return SZMenuResult(null, false)
    val rowHeight = theme.barHeight
    val font = theme.font
    val menuWidth = width ?: max(anchor.width, (items.maxOfOrNull { font.measure(it.label) + (it.shortcut?.let { s -> font.measure(s) + 12 } ?: 0) } ?: 0) + 16)
    val fullHeight = items.size * rowHeight + 4
    var picked: Int? = null
    var dismissed = false
    overlay(key = id.part("overlay")) {
        val screen = SZRect(0, 0, ui.screen.width, ui.screen.height).offsetBy(-origin.x, -origin.y)
        val backdrop = interaction(screen, SZSense.CLICK or SZSense.WHEEL, id.part("backdrop"))
        if (backdrop.pressed && isPresented) dismissed = true
        if (isPresented && takeKey(SZKeyCode.Escape)) dismissed = true

        // Below the anchor, or above when there is no room.
        val below = anchor.maxY + 1 + fullHeight <= screen.maxY
        val x = min(anchor.x, screen.maxX - menuWidth)
        val visibleHeight = (fullHeight * reveal).roundToIntAway()
        val full = SZRect(x, if (below) anchor.maxY + 1 else anchor.y - 1 - fullHeight, menuWidth, fullHeight)
        val shownRect = if (below) SZRect(full.x, full.y, full.width, visibleHeight) else SZRect(full.x, full.maxY - visibleHeight, full.width, visibleHeight)
        interaction(shownRect, SZSense.CLICK or SZSense.WHEEL, id.part("panel"))
        fill(SZRect(shownRect.x + 2, shownRect.y + 2, shownRect.width, shownRect.height), theme.background, SZPatterns.HALF)
        fill(shownRect, theme.panel)
        stroke(shownRect, theme.textDim)
        clip(shownRect.inset(1)) {
            items.forEachIndexed { i, item ->
                val row = SZRect(full.x + 2, full.y + 2 + i * rowHeight, full.width - 4, rowHeight)
                val itemID = id.child(12, i)
                var it = interaction(row, if (item.disabled) SZSense.NONE else SZSense.CLICK, itemID)
                if (item.disabled) it = SZInteraction(itemID)
                val traits = when {
                    item.disabled -> SZAccessibilityTraits.BUTTON or SZAccessibilityTraits.NOT_ENABLED
                    i == selected -> SZAccessibilityTraits.BUTTON or SZAccessibilityTraits.SELECTED
                    else -> SZAccessibilityTraits.BUTTON
                }
                accessibility(row, item.label, traits = traits, id = itemID)
                hint(item.hint, it, row)
                if (it.clicked && isPresented && reveal > 0.9) picked = i
                val isSelected = i == selected
                if (it.isActive) {
                    fill(row, if (it.held) theme.accentBright else theme.accent)
                } else if (isSelected) {
                    fill(SZRect(row.x, row.y + 2, 2, row.height - 4), theme.accent)
                }
                val ink = when {
                    item.disabled -> theme.textDim
                    it.isActive -> theme.background
                    isSelected -> theme.paper
                    else -> theme.text
                }
                text(item.label, SZPoint(row.x + 6, theme.textTop(row.y, row.height)), font, ink)
                item.shortcut?.let { shortcut ->
                    text(shortcut, SZPoint(row.maxX - 4 - font.measure(shortcut), theme.textTop(row.y, row.height)), font, if (it.isActive) theme.background else theme.textDim)
                }
            }
        }
    }
    return SZMenuResult(picked, dismissed)
}

// Dropdown

/** A button showing the current option that opens a menu of the others. Returns the option picked this frame. */
fun SZContext.dropdown(
    options: List<SZMenuItem>,
    selected: Int?,
    placeholder: String = "SELECT",
    width: Int = 90,
    disabled: Boolean = false,
    hint: String? = null,
    key: Any? = null,
): Int? {
    val theme = theme
    val id = makeKey(key)
    val frame = place(SZSize(width, theme.barHeight))
    val open = state(id = id.part("open")) { false }
    var it = interaction(frame, if (disabled) SZSense.NONE else SZSense.BUTTON, id)
    if (disabled) it = SZInteraction(id)
    val title = selected?.let { options.getOrNull(it)?.label } ?: placeholder
    accessibility(
        frame,
        title,
        if (open.value) "OPEN" else null,
        if (disabled) SZAccessibilityTraits.BUTTON or SZAccessibilityTraits.NOT_ENABLED else SZAccessibilityTraits.BUTTON,
        id,
    )
    hint(hint, it, frame)
    if (it.clicked) open.value = !open.value
    val tone = theme.tone(it, open.value, disabled)
    fill(frame, tone.fill)
    stroke(frame, tone.border)
    text(title, SZPoint(frame.x + 4, theme.textTop(frame.y, frame.height)), theme.font, if (selected == null) theme.textDim else tone.ink)
    val chevron = if (open.value) SZIcons.chevronUp else SZIcons.chevronDown
    bitmap(chevron, SZPoint(frame.maxX - 9, frame.y + (frame.height - chevron.height) / 2), tone.ink)
    val result = menu(open.value, options, frame, selected, width, id.part("menu"))
    if (result.dismissed) open.value = false
    result.picked?.let {
        open.value = false
        return it
    }
    return null
}

// Status line and tooltip

private class Posted(var serial: Int, var time: Double)

/**
 * A one-line footer: shows the current hint, or `text`; a message slides in
 * from below and leaves after `duration`. Bump `serial` to post a new message.
 */
fun SZContext.statusLine(
    text: String,
    message: String? = null,
    serial: Int = 0,
    duration: Double = 2.5,
    rect: SZRect,
) {
    val theme = theme
    val id = makeKey()
    val posted = state(id = id.part("posted")) { Posted(serial, Double.NEGATIVE_INFINITY) }
    if (posted.value.serial != serial) {
        posted.value.serial = serial
        posted.value.time = time
    }
    val age = time - posted.value.time
    val showing = message != null && age < duration
    if (showing) requestFrame()
    val slide = animatedValue(if (showing) 1.0 else 0.0, id.part("slide"), easing = 0.3, snap = 0.02)

    fill(rect, theme.background)
    fill(SZRect(rect.x, rect.y, rect.width, 1), theme.line)
    clip(rect) {
        val base = theme.textTop(rect.y, rect.height)
        val currentHint = ui.hint
        text(currentHint ?: text, SZPoint(rect.x + 4, base - (slide * rect.height).roundToIntAway()), theme.font, if (currentHint == null) theme.textDim else theme.paper)
        if (message != null && slide > 0) {
            val y = base + ((1 - slide) * rect.height).roundToIntAway()
            fill(SZRect(rect.x + 1, y - 2, 3, theme.font.glyphHeight + 2), theme.accent)
            text(message, SZPoint(rect.x + 8, y), theme.font, theme.accentBright)
        }
    }
    if (message != null && showing) accessibility(rect, message, traits = SZAccessibilityTraits.STATIC_TEXT, id = id.part("message"))
}

/** Shows the current hint in a box above what is touched, after `delay` seconds of holding it. */
fun SZContext.tooltip(delay: Double = 0.5) {
    val theme = theme
    val hint = ui.hint ?: return
    val age = ui.hintAge
    if (age < delay) {
        requestFrame()
        return
    }
    val reveal = min((age - delay) / 0.12, 1.0)
    if (reveal < 1) requestFrame()
    val anchor = ui.hintAnchor.offsetBy(-origin.x, -origin.y)
    val layout = layoutText(hint, theme.font, 160)
    val size = SZSize(layout.width + 8, layout.height + 5)
    val screen = SZRect(0, 0, ui.screen.width, ui.screen.height).offsetBy(-origin.x, -origin.y)
    val x = min(max(anchor.x + (anchor.width - size.width) / 2, screen.x + 2), screen.maxX - size.width - 2)
    val above = anchor.y - size.height - 4 >= screen.y
    val y = if (above) anchor.y - size.height - 4 else anchor.maxY + 4
    overlay(40) {
        val box = SZRect(x, y, size.width, size.height)
        fill(box.offsetBy(2, 2), theme.background, SZPatterns.HALF)
        fill(box, theme.paper, if (reveal < 1) SZPatterns.shade(reveal) else SZPatterns.SOLID)
        if (reveal >= 1) text(layout, SZPoint(x + 4, y + 3), theme.font, theme.background)
    }
}
