package com.leon.synthez.core

import kotlin.math.max
import kotlin.math.min

/** Keeps nested regions from reaching for an outer region's layout by accident; name the outer one with a label. */
@DslMarker
annotation class SZDsl

/**
 * A region of the screen during one frame: where to lay out, what to clip
 * to, which layer to draw on, and the identity its children derive from.
 *
 * Coordinates are local: [bounds] starts at (0, 0) for a fresh region and
 * shrinks as edges are cut off.
 *
 * ```kotlin
 * ui.frame(time, size, events) {
 *     fill(bounds, color = 0)
 *     inset(8)
 *     row(height = 11, gap = 3) {
 *         if (button("-").clicked) count--
 *         label(count.toString())
 *         if (button("+").clicked) count++
 *     }
 * }
 * ```
 *
 * Identity follows call order within a region: the third [interaction] of a
 * region gets the same [SZID] every frame. Pass a key where order can
 * change, such as rows of a list; a key is a String, an Int, a Long or an
 * [SZID].
 */
@SZDsl
class SZContext internal constructor(
    val ui: SZUI,
    internal val drawList: SZDrawList,
    val id: SZID,
    /** The absolute position of local (0, 0). */
    val origin: SZPoint,
    /** The space still free, in local coordinates. */
    var bounds: SZRect,
    /** The absolute clip rectangle. */
    val clipRect: SZRect,
    internal val clipIndex: Int,
    val layer: Int,
) {
    /** Where the next [place] goes, in local coordinates. */
    var cursor: SZPoint = bounds.origin
    var flow = SZAxis.VERTICAL
    var gap = 0
    var align = SZAlignment.START

    /** The farthest point anything was placed or cut up to, in local coordinates: a region's natural size. */
    var reach = SZPoint.ZERO
        private set

    private var placedCount = 0
    private var regionCounter = 0
    private var interactionCounter = 0
    private var stateCounter = 0
    private var animationCounter = 0
    private var keyCounter = 0

    // Frame info

    val time: Double get() = ui.time
    val dt: Double get() = ui.dt
    val palette: SZPalette get() = ui.palette

    fun requestFrame() = ui.requestFrame()

    fun requestFrame(at: Double) = ui.requestFrame(at)

    fun absolute(rect: SZRect): SZRect = rect.offsetBy(origin.x, origin.y)

    fun absolute(p: SZPoint): SZPoint = p + origin

    // Identity and state

    /** An ID for a widget: `key`'s if given, the next position otherwise. Widgets call this once and name their parts with [SZID.part]. */
    fun makeKey(key: Any? = null): SZID = if (key != null) szKey(key) else id.child(1, keyCounter++)

    /** State kept as long as this call happens every frame. A key must always hold the same type of value. */
    fun <T> state(key: Any? = null, initial: () -> T): SZState<T> {
        val stateID = if (key != null) szKey(key) else id.child(2, stateCounter++)
        return ui.state(stateID, initial)
    }

    fun <T> state(id: SZID, initial: () -> T): SZState<T> = ui.state(id, initial)

    /** `target`, reached smoothly: the value eases toward it frame by frame and the host keeps drawing until it arrives. */
    fun animatedValue(target: Double, key: Any? = null, easing: Double = 0.15, snap: Double = 0.5, from: Double? = null): Double {
        val animationID = if (key != null) szKey(key) else id.child(3, animationCounter++)
        return ui.animatedValue(animationID, target, easing, snap, from)
    }

    fun animatedValue(target: Double, id: SZID, easing: Double = 0.15, snap: Double = 0.5, from: Double? = null): Double =
        ui.animatedValue(id, target, easing, snap, from)

    // Input

    /** Registers `rect` (local) for input and returns what happened to it. */
    fun interaction(rect: SZRect, sense: Int = SZSense.BUTTON, key: Any? = null): SZInteraction {
        val interactionID = if (key != null) szKey(key) else id.child(4, interactionCounter++)
        return interaction(rect, sense, interactionID)
    }

    fun interaction(rect: SZRect, sense: Int = SZSense.BUTTON, id: SZID): SZInteraction =
        ui.interaction(id, absolute(rect).intersection(clipRect), sense, layer)

    /** Whether the pointer is inside `rect` (local) and no higher layer covers it, ignoring rectangles on this layer. */
    fun containsPointer(rect: SZRect): Boolean {
        val p = ui.pointer ?: return false
        if (!absolute(rect).intersection(clipRect).contains(p)) return false
        return !ui.isCovered(p, layer)
    }

    // Layout

    /**
     * Takes `size` at the cursor along [flow] and advances past it. A zero
     * cross-axis size, or [SZAlignment.STRETCH], spans the region.
     */
    fun place(size: SZSize): SZRect {
        val g = if (placedCount > 0) gap else 0
        placedCount++
        val rect = when (flow) {
            SZAxis.VERTICAL -> {
                val width = if (size.width == 0 || align == SZAlignment.STRETCH) bounds.width else size.width
                val y = cursor.y + g
                val x = when (align) {
                    SZAlignment.START, SZAlignment.STRETCH -> bounds.x
                    SZAlignment.CENTER -> bounds.x + (bounds.width - width) / 2
                    SZAlignment.END -> bounds.maxX - width
                }
                SZRect(x, y, width, size.height).also { cursor = SZPoint(cursor.x, it.maxY) }
            }
            SZAxis.HORIZONTAL -> {
                val height = if (size.height == 0 || align == SZAlignment.STRETCH) bounds.height else size.height
                val x = cursor.x + g
                val y = when (align) {
                    SZAlignment.START, SZAlignment.STRETCH -> bounds.y
                    SZAlignment.CENTER -> bounds.y + (bounds.height - height) / 2
                    SZAlignment.END -> bounds.maxY - height
                }
                SZRect(x, y, size.width, height).also { cursor = SZPoint(it.maxX, cursor.y) }
            }
        }
        extend(rect)
        return rect
    }

    fun place(width: Int, height: Int): SZRect = place(SZSize(width, height))

    /** Leaves `amount` empty pixels along [flow]. */
    fun space(amount: Int) {
        cursor = when (flow) {
            SZAxis.VERTICAL -> SZPoint(cursor.x, cursor.y + amount)
            SZAxis.HORIZONTAL -> SZPoint(cursor.x + amount, cursor.y)
        }
    }

    fun inset(top: Int, right: Int, bottom: Int, left: Int) {
        bounds = bounds.inset(top, right, bottom, left)
        clampCursor()
    }

    fun inset(amount: Int) = inset(amount, amount, amount, amount)

    /** Cuts a strip `size` tall off the top of [bounds] and returns it. */
    fun cutTop(size: Int, gap: Int = 0): SZRect {
        val s = size.coerceIn(0, max(bounds.height, 0))
        val rect = SZRect(bounds.x, bounds.y, bounds.width, s)
        bounds = bounds.inset(min(s + gap, bounds.height), 0, 0, 0)
        clampCursor()
        extend(rect)
        return rect
    }

    fun cutBottom(size: Int, gap: Int = 0): SZRect {
        val s = size.coerceIn(0, max(bounds.height, 0))
        val rect = SZRect(bounds.x, bounds.maxY - s, bounds.width, s)
        bounds = bounds.inset(0, 0, min(s + gap, bounds.height), 0)
        extend(rect)
        return rect
    }

    fun cutLeft(size: Int, gap: Int = 0): SZRect {
        val s = size.coerceIn(0, max(bounds.width, 0))
        val rect = SZRect(bounds.x, bounds.y, s, bounds.height)
        bounds = bounds.inset(0, 0, 0, min(s + gap, bounds.width))
        clampCursor()
        extend(rect)
        return rect
    }

    fun cutRight(size: Int, gap: Int = 0): SZRect {
        val s = size.coerceIn(0, max(bounds.width, 0))
        val rect = SZRect(bounds.maxX - s, bounds.y, s, bounds.height)
        bounds = bounds.inset(0, min(s + gap, bounds.width), 0, 0)
        extend(rect)
        return rect
    }

    private fun clampCursor() {
        cursor = SZPoint(max(cursor.x, bounds.x), max(cursor.y, bounds.y))
    }

    private fun extend(rect: SZRect) {
        if (rect.maxX > reach.x || rect.maxY > reach.y) reach = SZPoint(max(reach.x, rect.maxX), max(reach.y, rect.maxY))
    }

    // Regions

    /**
     * Runs `body` in a child region covering `rect` (local). The child's
     * coordinates start at (0, 0).
     *
     * @param reserve Whether `rect` counts toward this region's [reach]. Pass false for a region sized only to measure its content.
     */
    fun <R> region(rect: SZRect, clip: Boolean = false, key: Any? = null, reserve: Boolean = true, body: SZContext.() -> R): R {
        if (reserve) extend(rect)
        return makeChild(rect, clip, key).body()
    }

    /**
     * Runs `body` on the layer above, over the whole screen's clip, in this
     * region's coordinates: menus and sheets escape scroll views this way.
     *
     * @param levels How many layers up; tooltips go far above everything else.
     */
    fun <R> overlay(levels: Int = 1, key: Any? = null, body: SZContext.() -> R): R {
        val childID = if (key != null) szKey(key) else id.child(5, nextRegion())
        val child = SZContext(ui, drawList, childID, origin, bounds, ui.screen, 0, layer + max(levels, 1))
        child.cursor = cursor
        return child.body()
    }

    /** Clips `body`'s drawing and input to `rect` (local), in this region's coordinates. */
    fun <R> clip(rect: SZRect, body: SZContext.() -> R): R {
        val c = absolute(rect).intersection(clipRect)
        val child = SZContext(ui, drawList, id.child(6, nextRegion()), origin, rect, c, drawList.addClip(c), layer)
        val result = child.body()
        extend(rect)
        return result
    }

    /** A row from the cursor to the far edge: children flow left to right, and this region advances by what they used (or `width`/`height`). */
    fun <R> row(
        width: Int? = null,
        height: Int? = null,
        gap: Int = 0,
        align: SZAlignment = SZAlignment.START,
        key: Any? = null,
        body: SZContext.() -> R,
    ): R = stack(SZAxis.HORIZONTAL, width, height, gap, align, key, body)

    /** A column from the cursor to the far edge: children flow top to bottom, and this region advances by what they used. */
    fun <R> column(
        width: Int? = null,
        height: Int? = null,
        gap: Int = 0,
        align: SZAlignment = SZAlignment.START,
        key: Any? = null,
        body: SZContext.() -> R,
    ): R = stack(SZAxis.VERTICAL, width, height, gap, align, key, body)

    private fun <R> stack(
        axis: SZAxis,
        width: Int?,
        height: Int?,
        gap: Int,
        align: SZAlignment,
        key: Any?,
        body: SZContext.() -> R,
    ): R {
        val leading = if (placedCount > 0) this.gap else 0
        val available = when (flow) {
            SZAxis.VERTICAL -> SZRect(bounds.x, cursor.y + leading, width ?: bounds.width, height ?: max(bounds.maxY - cursor.y - leading, 0))
            SZAxis.HORIZONTAL -> SZRect(cursor.x + leading, bounds.y, width ?: max(bounds.maxX - cursor.x - leading, 0), height ?: bounds.height)
        }
        val child = makeChild(available, false, key)
        child.flow = axis
        child.gap = gap
        child.align = align
        val result = child.body()
        // Advance as if the child had been placed at its natural size.
        placedCount++
        val rect = SZRect(available.x, available.y, width ?: child.reach.x, height ?: child.reach.y)
        cursor = when (flow) {
            SZAxis.VERTICAL -> SZPoint(cursor.x, rect.maxY)
            SZAxis.HORIZONTAL -> SZPoint(rect.maxX, cursor.y)
        }
        extend(rect)
        return result
    }

    private fun nextRegion(): Int = regionCounter++

    private fun makeChild(rect: SZRect, clip: Boolean, key: Any?): SZContext {
        val childID = if (key != null) szKey(key) else id.child(0, nextRegion())
        val abs = absolute(rect)
        val childClip = if (clip) abs.intersection(clipRect) else clipRect
        val index = if (clip) drawList.addClip(childClip) else clipIndex
        return SZContext(ui, drawList, childID, abs.origin, SZRect(0, 0, rect.width, rect.height), childClip, index, layer)
    }

    // Drawing

    private fun isVisible(abs: SZRect): Boolean =
        abs.maxX > clipRect.minX && abs.minX < clipRect.maxX && abs.maxY > clipRect.minY && abs.minY < clipRect.maxY

    fun fill(rect: SZRect, color: SZColor, pattern: SZPattern = SZPatterns.SOLID) {
        val abs = absolute(rect)
        if (abs.isEmpty || pattern == 0 || !isVisible(abs)) return
        drawList.append(SZDrawOp.Fill(abs, color, pattern), clipIndex, layer)
    }

    fun stroke(rect: SZRect, color: SZColor) {
        val abs = absolute(rect)
        if (abs.isEmpty || !isVisible(abs)) return
        drawList.append(SZDrawOp.Stroke(abs, color), clipIndex, layer)
    }

    fun line(from: SZPoint, to: SZPoint, color: SZColor) {
        drawList.append(SZDrawOp.Line(absolute(from), absolute(to), color), clipIndex, layer)
    }

    fun pixel(p: SZPoint, color: SZColor) {
        val abs = absolute(p)
        if (!clipRect.contains(abs)) return
        drawList.append(SZDrawOp.Pixel(abs, color), clipIndex, layer)
    }

    fun circle(center: SZPoint, radius: Int, color: SZColor, filled: Boolean = true) {
        val c = absolute(center)
        if (!isVisible(SZRect(c.x - radius, c.y - radius, radius * 2 + 1, radius * 2 + 1))) return
        drawList.append(SZDrawOp.Circle(c, radius, color, filled), clipIndex, layer)
    }

    fun bitmap(bitmap: SZBitmap, at: SZPoint, color: SZColor, scale: Int = 1) {
        val abs = absolute(at)
        if (!isVisible(SZRect(abs.x, abs.y, bitmap.width * scale, bitmap.height * scale))) return
        drawList.append(SZDrawOp.Bitmap(bitmap, abs, color, scale), clipIndex, layer)
    }

    /** Draws a palette-indexed image with its top-left at `at`. */
    fun image(image: SZImage, at: SZPoint, scale: Int = 1) {
        val abs = absolute(at)
        if (!isVisible(SZRect(abs.x, abs.y, image.width * scale, image.height * scale))) return
        drawList.append(SZDrawOp.Image(image, abs, scale), clipIndex, layer)
    }

    /** Draws one line of text with its top-left at `at` and returns its width. */
    fun text(text: CharSequence, at: SZPoint, font: SZBitmapFont, color: SZColor, scale: Int = 1): Int {
        val abs = absolute(at)
        val height = font.glyphHeight * scale
        if (abs.y >= clipRect.maxY || abs.y + height <= clipRect.minY || abs.x >= clipRect.maxX) return font.measure(text) * scale
        return drawList.appendText(text, font, abs, color, scale, clipIndex, layer)
    }

    /** Maps what is already drawn in `rect` through `map`. */
    fun remap(rect: SZRect, map: SZColorMap, pattern: SZPattern = SZPatterns.SOLID) {
        val abs = absolute(rect)
        if (abs.isEmpty || !isVisible(abs)) return
        drawList.append(SZDrawOp.Remap(abs, map, pattern), clipIndex, layer)
    }

    fun mosaic(rect: SZRect, size: Int) {
        val abs = absolute(rect)
        if (size <= 1 || abs.isEmpty || !isVisible(abs)) return
        drawList.append(SZDrawOp.Mosaic(abs, size), clipIndex, layer)
    }
}
