package com.leon.synthez.widgets

import com.leon.synthez.core.SZAccessibilityTraits
import com.leon.synthez.core.SZContext
import com.leon.synthez.core.SZEase
import com.leon.synthez.core.SZEdge
import com.leon.synthez.core.SZInteraction
import com.leon.synthez.core.SZPatterns
import com.leon.synthez.core.SZPoint
import com.leon.synthez.core.SZPointerKind
import com.leon.synthez.core.SZRect
import com.leon.synthez.core.SZSense
import com.leon.synthez.core.SZSize
import com.leon.synthez.core.accessibility
import com.leon.synthez.core.canCapturePointer
import com.leon.synthez.core.capturePointer
import com.leon.synthez.core.filter
import com.leon.synthez.core.isPointerDown
import com.leon.synthez.core.pointer
import com.leon.synthez.core.pointerVelocity
import com.leon.synthez.core.pressOrigin
import com.leon.synthez.core.ramp
import com.leon.synthez.core.roundToIntAway
import com.leon.synthez.core.roundedAway
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Which axes a scroll view moves along, as flags. */
object SZScrollAxes {
    const val HORIZONTAL = 1
    const val VERTICAL = 2
    const val BOTH = HORIZONTAL or VERTICAL
}

/** What a scroll view's content sees. */
data class SZScroll(
    /** The scroll position: content coordinates at the viewport's top-left. */
    val offset: SZPoint,
    /** The visible part of the content, in content coordinates. */
    val visible: SZRect,
    val isDragging: Boolean,
    val isMoving: Boolean,
)

/** Scroll state kept across frames. */
private class ScrollState {
    var x = 0.0
    var y = 0.0
    var vx = 0.0
    var vy = 0.0
    var content = SZSize.ZERO
    var dragging = false
    var startX = 0.0
    var startY = 0.0
    var targetX = 0.0
    var targetY = 0.0
    var hasTarget = false
    var lastMotion = Double.NEGATIVE_INFINITY
    var revealed: SZRect? = null
}

/** Rubber-band resistance past an edge: the further out, the harder. */
private fun rubberBand(overshoot: Double, dimension: Double): Double {
    val c = 0.55
    val sign = if (overshoot < 0) -1.0 else 1.0
    val x = abs(overshoot)
    return sign * (1 - 1 / (x * c / max(dimension, 1.0) + 1)) * dimension
}

/**
 * Scrolls `body`'s content: drag with inertia and rubber-banding, wheel,
 * optional snapping, edge fades, and an indicator while moving.
 *
 * The content's size is `content` if given, otherwise what the content
 * reached last frame. Content drawn outside the viewport is culled and
 * cannot be touched.
 */
fun <R> SZContext.scrollView(
    axes: Int = SZScrollAxes.VERTICAL,
    content: SZSize? = null,
    snap: SZSize? = null,
    fade: Int = 0,
    indicators: Boolean = true,
    reveal: SZRect? = null,
    key: Any? = null,
    rect: SZRect? = null,
    body: SZContext.(SZScroll) -> R,
): R {
    val theme = theme
    val id = makeKey(key)
    val frame = rect ?: place(SZSize(0, max(bounds.maxY - cursor.y, 0)))
    val state = state(id = id.part("scroll")) { ScrollState() }.value
    val horizontal = axes and SZScrollAxes.HORIZONTAL != 0
    val vertical = axes and SZScrollAxes.VERTICAL != 0
    // Under the content, so controls inside take taps first.
    val it = interaction(frame, SZSense.WHEEL, id)

    val measured = content ?: state.content
    val maxX = if (horizontal) max(measured.width - frame.width, 0).toDouble() else 0.0
    val maxY = if (vertical) max(measured.height - frame.height, 0).toDouble() else 0.0
    val dt = dt

    // Scroll so `reveal` (content coordinates) is centered, once each time it changes and not while dragging.
    if (reveal != null && reveal != state.revealed && !state.dragging) {
        state.revealed = reveal
        if (horizontal) state.targetX = (reveal.x + reveal.width / 2.0 - frame.width / 2.0).coerceIn(0.0, maxX) else state.targetX = state.x
        if (vertical) state.targetY = (reveal.y + reveal.height / 2.0 - frame.height / 2.0).coerceIn(0.0, maxY) else state.targetY = state.y
        state.hasTarget = true
        state.vx = 0.0
        state.vy = 0.0
    }
    val wasMoving = state.dragging || state.vx != 0.0 || state.vy != 0.0 || state.hasTarget

    // Take the pointer once a drag inside us passes the slop along our axis.
    val p = pointer
    if (!state.dragging && isPointerDown && canCapturePointer(id) && p != null) {
        val start = pressOrigin
        val inside = absolute(frame).intersection(clipRect).contains(absolute(start))
        val dx = (p.x - start.x).toDouble()
        val dy = (p.y - start.y).toDouble()
        val slop = 4.0
        val wantsX = horizontal && abs(dx) > slop && (vertical || abs(dx) > abs(dy))
        val wantsY = vertical && abs(dy) > slop && (horizontal || abs(dy) > abs(dx))
        // Like UIScrollView, a press during a fling only stops it: the
        // content under the finger moved, so it must not be tapped.
        val catchesFling = abs(state.vx) > 40 || abs(state.vy) > 40
        if (inside && (wantsX || wantsY || catchesFling)) {
            capturePointer(id)
            state.dragging = true
            state.startX = state.x
            state.startY = state.y
            state.vx = 0.0
            state.vy = 0.0
            state.hasTarget = false
        }
    }

    // Read again without registering: capture may have happened just now,
    // and dx/dy then run from the capture point.
    val live = interaction(SZRect.ZERO, SZSense.NONE, id)
    if (state.dragging && live.held) {
        var nx = state.startX - live.dx
        var ny = state.startY - live.dy
        // Pulling past an edge gives a little (a quarter of the view at most), not a page of blank.
        if (nx < 0) nx = rubberBand(nx, frame.width / 4.0)
        if (nx > maxX) nx = maxX + rubberBand(nx - maxX, frame.width / 4.0)
        if (ny < 0) ny = rubberBand(ny, frame.height / 4.0)
        if (ny > maxY) ny = maxY + rubberBand(ny - maxY, frame.height / 4.0)
        if (horizontal) state.x = nx
        if (vertical) state.y = ny
        state.lastMotion = time
    } else if (state.dragging) {
        // Released: fling.
        state.dragging = false
        val v = pointerVelocity
        state.vx = if (horizontal) -v.x else 0.0
        state.vy = if (vertical) -v.y else 0.0
        if (snap != null) {
            // Land on the snap grid nearest to where the fling would stop.
            val tau = 0.325
            var tx = state.x + state.vx * tau
            var ty = state.y + state.vy * tau
            if (snap.width > 0) tx = (tx / snap.width).roundedAway() * snap.width
            if (snap.height > 0) ty = (ty / snap.height).roundedAway() * snap.height
            state.targetX = tx.coerceIn(0.0, maxX)
            state.targetY = ty.coerceIn(0.0, maxY)
            state.hasTarget = true
            state.vx = 0.0
            state.vy = 0.0
        }
        state.lastMotion = time
    } else {
        // Wheel.
        if (it.wheelX != 0.0 || it.wheelY != 0.0) {
            val baseX = if (state.hasTarget) state.targetX else state.x
            val baseY = if (state.hasTarget) state.targetY else state.y
            var tx = (baseX + if (horizontal) it.wheelX else 0.0).coerceIn(0.0, maxX)
            val wheelY = if (vertical) it.wheelY else if (axes == SZScrollAxes.HORIZONTAL) it.wheelY else 0.0
            var ty = (baseY + wheelY).coerceIn(0.0, maxY)
            if (snap != null && snap.width > 0) tx = (tx / snap.width).roundedAway() * snap.width
            if (snap != null && snap.height > 0) ty = (ty / snap.height).roundedAway() * snap.height
            state.targetX = tx
            state.targetY = ty
            state.hasTarget = true
            state.vx = 0.0
            state.vy = 0.0
        }
        // Inertia, then spring back inside the edges.
        if (state.vx != 0.0 || state.vy != 0.0) {
            val decay = 0.998.pow(dt * 1000)
            state.x += state.vx * dt
            state.y += state.vy * dt
            state.vx *= decay
            state.vy *= decay
            // A fling that reaches an edge stops within a few pixels and springs back.
            if (state.x < 0 || state.x > maxX) state.vx *= 0.5.pow(dt * 60)
            if (state.y < 0 || state.y > maxY) state.vy *= 0.5.pow(dt * 60)
            val limitX = min(frame.width / 12.0, 24.0)
            val limitY = min(frame.height / 12.0, 24.0)
            if (state.x < -limitX || state.x > maxX + limitX) {
                state.x = state.x.coerceIn(-limitX, maxX + limitX)
                state.vx = 0.0
            }
            if (state.y < -limitY || state.y > maxY + limitY) {
                state.y = state.y.coerceIn(-limitY, maxY + limitY)
                state.vy = 0.0
            }
            if (abs(state.vx) < 8) state.vx = 0.0
            if (abs(state.vy) < 8) state.vy = 0.0
            state.lastMotion = time
        }
        if (state.vx == 0.0 && state.vy == 0.0) {
            val goalX = if (state.hasTarget) state.targetX else state.x.coerceIn(0.0, maxX)
            val goalY = if (state.hasTarget) state.targetY else state.y.coerceIn(0.0, maxY)
            val nx = SZEase.approach(state.x, goalX, dt, easing = 0.2, snap = 0.3)
            val ny = SZEase.approach(state.y, goalY, dt, easing = 0.2, snap = 0.3)
            if (nx != state.x || ny != state.y) state.lastMotion = time
            state.x = nx
            state.y = ny
            if (state.hasTarget && state.targetX == nx && state.targetY == ny) state.hasTarget = false
        }
    }
    val isMoving = state.dragging || state.vx != 0.0 || state.vy != 0.0 || state.hasTarget ||
        state.x < 0 || state.x > maxX || state.y < 0 || state.y > maxY
    if (isMoving || wasMoving) requestFrame()

    val ox = state.x.roundToIntAway()
    val oy = state.y.roundToIntAway()
    val contentWidth = if (horizontal) max(measured.width, frame.width) else frame.width
    val contentHeight = if (vertical) max(measured.height, frame.height) else frame.height
    val info = SZScroll(SZPoint(ox, oy), SZRect(ox, oy, frame.width, frame.height), state.dragging, isMoving)

    var reached = SZSize.ZERO
    val result = region(frame, clip = true, key = id.part("view")) {
        val unbounded = Short.MAX_VALUE.toInt()
        val inner = SZRect(
            -ox,
            -oy,
            if (horizontal && content == null) unbounded else contentWidth,
            if (vertical && content == null) unbounded else contentHeight,
        )
        val r = region(inner, key = id.part("content"), reserve = false) {
            if (content == null) {
                // Lay out in the viewport's width (or height) on the fixed axis.
                if (!horizontal) bounds = bounds.copy(width = frame.width)
                if (!vertical) bounds = bounds.copy(height = frame.height)
            }
            val r = body(info)
            reached = SZSize(reach.x, reach.y)
            r
        }

        // Fade the edges by how much content lies beyond them.
        if (fade > 0) {
            fun edge(band: SZRect, side: SZEdge, beyond: Double) {
                val s = (beyond / fade).coerceIn(0.0, 1.0)
                if (s <= 0) return
                filter(band, ramp(band, side, s), theme.background)
            }
            if (vertical) {
                edge(SZRect(0, 0, frame.width, fade), SZEdge.TOP, state.y)
                edge(SZRect(0, frame.height - fade, frame.width, fade), SZEdge.BOTTOM, maxY - state.y)
            }
            if (horizontal) {
                edge(SZRect(0, 0, fade, frame.height), SZEdge.LEFT, state.x)
                edge(SZRect(frame.width - fade, 0, fade, frame.height), SZEdge.RIGHT, maxX - state.x)
            }
        }

        // Indicators show while moving and dissolve after.
        if (indicators) {
            val age = time - state.lastMotion
            val visibility = if (isMoving) 1.0 else max(0.0, 1 - (age - 0.6) / 0.3)
            if (visibility > 0) {
                if (!isMoving) requestFrame()
                val pattern = SZPatterns.shade(visibility)
                if (vertical && maxY > 0) {
                    val length = max(frame.height * frame.height / max(contentHeight, 1), 8)
                    val travel = (frame.height - length - 2).toDouble()
                    val pos = ((state.y / maxY).coerceIn(0.0, 1.0) * travel).roundToIntAway()
                    fill(SZRect(frame.width - 3, 1 + pos, 2, length), theme.textDim, pattern)
                }
                if (horizontal && maxX > 0) {
                    val length = max(frame.width * frame.width / max(contentWidth, 1), 8)
                    val travel = (frame.width - length - 2).toDouble()
                    val pos = ((state.x / maxX).coerceIn(0.0, 1.0) * travel).roundToIntAway()
                    fill(SZRect(1 + pos, frame.height - 3, length, 2), theme.textDim, pattern)
                }
            }
        }
        r
    }

    if (content == null && reached != state.content) {
        state.content = reached
        requestFrame()
    }
    return result
}

// List

/** What a list row is doing. */
data class SZListRow(
    val index: Int,
    /** The row's rectangle, in the row's own coordinates. */
    val rect: SZRect,
    val selected: Boolean,
    val interaction: SZInteraction,
)

/**
 * A virtual list: only visible rows are built, so 10,000 rows cost as much as a screenful. Returns the index tapped this frame.
 * Changing `reveal` scrolls that row to the middle (the timetable's "now playing").
 */
fun SZContext.list(
    count: Int,
    rowHeight: Int,
    selected: Int?,
    separators: Boolean = true,
    fade: Int = 12,
    reveal: Int? = null,
    key: Any? = null,
    rect: SZRect? = null,
    rowBody: SZContext.(SZListRow) -> Unit,
): Int? {
    val theme = theme
    val id = makeKey(key)
    val frame = rect ?: place(SZSize(0, max(bounds.maxY - cursor.y, 0)))
    fill(frame, theme.background)
    stroke(frame, theme.line)
    val inner = frame.inset(1)
    val revealRect = reveal?.takeIf { it in 0 until count }?.let { SZRect(0, it * rowHeight, inner.width, rowHeight) }
    return scrollView(SZScrollAxes.VERTICAL, SZSize(inner.width, count * rowHeight), fade = fade, reveal = revealRect, key = id.part("scroll"), rect = inner) { scroll ->
        val first = max(scroll.visible.minY / rowHeight, 0)
        val last = min((scroll.visible.maxY + rowHeight - 1) / rowHeight, count)
        var tapped: Int? = null
        for (index in first until last) {
            val r = SZRect(0, index * rowHeight, inner.width, rowHeight)
            val rowID = id.child(8, index)
            val it = interaction(r, id = rowID)
            if (it.clicked) tapped = index
            val isSelected = index == selected
            when {
                isSelected -> fill(r, theme.accentDim)
                it.held -> fill(r, theme.lineStrong)
                it.hovered || containsPointer(r) && ui.pointerKind == SZPointerKind.MOUSE -> fill(r, theme.panel)
            }
            if (separators && !isSelected) fill(SZRect(r.x + 4, r.maxY - 1, r.width - 8, 1), theme.line, SZPatterns.HALF)
            region(r, key = rowID.part("row")) {
                rowBody(SZListRow(index, SZRect(0, 0, r.width, r.height), isSelected, it))
            }
            accessibility(
                r,
                "ROW $index",
                traits = if (isSelected) SZAccessibilityTraits.BUTTON or SZAccessibilityTraits.SELECTED else SZAccessibilityTraits.BUTTON,
                id = rowID,
            )
        }
        tapped
    }
}
