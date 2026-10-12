package com.leon.synthez.view

import android.graphics.RectF
import android.view.Choreographer
import com.leon.synthez.core.SZAccessibilityItem
import com.leon.synthez.core.SZCRTSettings
import com.leon.synthez.core.SZColor
import com.leon.synthez.core.SZContext
import com.leon.synthez.core.SZFrameResult
import com.leon.synthez.core.SZFrameScheduler
import com.leon.synthez.core.SZInputEvent
import com.leon.synthez.core.SZPalette
import com.leon.synthez.core.SZPoint
import com.leon.synthez.core.SZPointerKind
import com.leon.synthez.core.SZRect
import com.leon.synthez.core.SZResolution
import com.leon.synthez.core.SZScreen
import com.leon.synthez.core.SZSurface
import com.leon.synthez.core.SZUI
import kotlin.math.max

/**
 * Everything a canvas does besides talking to the Android view system: the
 * frame loop, the scheduler, the frame, rasterizing, handing frames to the
 * presenter, and mapping between view pixels and virtual pixels.
 *
 * Runs on the main thread. [SZCanvasView] owns one and forwards events to it.
 */
class SZCanvasEngine internal constructor(
    private val presenter: SZGLPresenter,
    private val requestRender: () -> Unit,
    palette: SZPalette,
    background: SZColor,
    resolution: SZResolution,
    scheduler: SZFrameScheduler,
) {
    val ui = SZUI(palette, background)

    /** Builds each frame. */
    var body: (SZContext.() -> Unit)? = null
        set(value) {
            field = value
            invalidate()
        }

    var resolution: SZResolution = resolution
        set(value) {
            if (value == field) return
            field = value
            relayout()
        }

    var scheduler: SZFrameScheduler = scheduler
        set(value) {
            field = value
            field.settleDuration = crt.afterglow
            wake()
        }

    /** The CRT post-process. While enabled, frames keep coming for the afterglow to fade after each change. */
    var crt = SZCRTSettings()
        set(value) {
            if (value == field) return
            field = value
            scheduler.settleDuration = value.afterglow
            needsPresent = true
            wake()
        }

    /** The virtual screen for the current size. */
    var screen: SZScreen = SZScreen.of(resolution, 1.0, 1.0)
        private set

    /** Frames run, rasterized and presented, for diagnostics. */
    var framesBuilt = 0
        private set
    var framesRasterized = 0
        private set
    var framesPresented = 0
        private set

    /** Called after each frame, for the keyboard and accessibility. */
    internal var onFrame: ((SZFrameResult) -> Unit)? = null

    private val surface = SZSurface(0, 0)
    private var pendingEvents = ArrayList<SZInputEvent>()
    private var frameEvents = ArrayList<SZInputEvent>()
    private var needsPresent = true
    private var needsUpload = true
    private var lastPresentTime: Double? = null
    private var deviceWidth = 0
    private var deviceHeight = 0
    private var density = 1.0

    // Frame loop: a Choreographer callback that keeps reposting itself while
    // there is work, like a display link that pauses when idle.
    private val choreographer = Choreographer.getInstance()
    private val frameCallback = Choreographer.FrameCallback { onVsync(it) }
    private var attached = false
    private var ticking = false
    private var callbackPosted = false
    private var postedDelayed = false
    private var nextDelayMillis = 0L

    // Frames

    /** Draws a new frame at the next refresh. Call when state the body reads changes from outside. */
    fun invalidate() {
        scheduler.invalidate()
        wake()
    }

    /** Queues an event for the next frame. */
    fun send(event: SZInputEvent) {
        pendingEvents.add(event)
        wake()
    }

    internal fun attach() {
        attached = true
        wake()
    }

    internal fun detach() {
        attached = false
        ticking = false
        if (callbackPosted) choreographer.removeFrameCallback(frameCallback)
        callbackPosted = false
    }

    internal fun wake() {
        if (!attached) return
        ticking = true
        nextDelayMillis = 0
        if (callbackPosted && postedDelayed) {
            choreographer.removeFrameCallback(frameCallback)
            callbackPosted = false
        }
        post()
    }

    private fun post() {
        if (callbackPosted) return
        callbackPosted = true
        postedDelayed = nextDelayMillis > 0
        if (postedDelayed) choreographer.postFrameCallbackDelayed(frameCallback, nextDelayMillis) else choreographer.postFrameCallback(frameCallback)
    }

    private fun onVsync(frameTimeNanos: Long) {
        callbackPosted = false
        if (!ticking || !attached) return
        nextDelayMillis = 0
        step(frameTimeNanos / 1e9)
        if (ticking) post()
    }

    /** Runs one refresh at `time` seconds. Exposed for tests and for hosts that drive frames themselves. */
    fun step(time: Double) {
        val body = body
        if (body == null || deviceWidth <= 0 || deviceHeight <= 0) {
            ticking = false
            return
        }
        if (!scheduler.isDue(time, pendingEvents.isNotEmpty()) && !needsPresent) {
            // Sleep until a timed wake (a caret blink) instead of ticking every refresh.
            val wakeAt = scheduler.wakeAt
            if (wakeAt == null) ticking = false else nextDelayMillis = max(((wakeAt - time) * 1000).toLong() - 4, 0)
            return
        }

        val events = pendingEvents
        pendingEvents = frameEvents
        frameEvents = events
        val result = ui.frame(time, screen.size, frameEvents, body)
        frameEvents.clear()
        framesBuilt++

        if (result.changed) {
            surface.render(ui.drawList)
            framesRasterized++
            needsPresent = true
            needsUpload = true
        }
        // Afterglow evolves even when the picture does not.
        if (crt.isEnabled) needsPresent = true
        if (needsPresent) {
            val dt = lastPresentTime?.let { (time - it).coerceIn(0.0, 0.1) } ?: (1.0 / 60)
            presenter.submit(surface, needsUpload, ui.palette, ui.background, screen, crt, dt)
            requestRender()
            needsPresent = false
            needsUpload = false
            lastPresentTime = time
            framesPresented++
        }
        onFrame?.invoke(result)
        val keepTicking = scheduler.didDraw(time, result) || needsPresent || pendingEvents.isNotEmpty()
        if (!keepTicking) ticking = false
    }

    // Geometry

    /** Sizes the virtual screen for a view of `width`×`height` pixels at `density` pixels per dp. */
    internal fun layout(width: Int, height: Int, density: Float) {
        deviceWidth = width
        deviceHeight = height
        this.density = density.toDouble()
        relayout()
    }

    private fun relayout() {
        if (deviceWidth <= 0 || deviceHeight <= 0) return
        val newScreen = SZScreen.of(resolution, deviceWidth.toDouble(), deviceHeight.toDouble(), density)
        if (newScreen != screen || surface.width != newScreen.size.width || surface.height != newScreen.size.height) {
            screen = newScreen
            surface.resize(newScreen.size.width, newScreen.size.height)
            scheduler.invalidate()
        }
        needsPresent = true
        needsUpload = true
        wake()
    }

    /** The virtual pixel under a point in the view, in pixels from its top left. */
    fun virtualPoint(x: Float, y: Float): SZPoint = screen.virtualPoint(x.toDouble(), y.toDouble())

    /** The view rectangle, in pixels from its top left, of a virtual rectangle. */
    fun viewRect(rect: SZRect): RectF = RectF(
        (screen.viewportX + rect.x * screen.scale).toFloat(),
        (screen.viewportY + rect.y * screen.scale).toFloat(),
        (screen.viewportX + rect.maxX * screen.scale).toFloat(),
        (screen.viewportY + rect.maxY * screen.scale).toFloat(),
    )

    /** Wheel deltas in view pixels, converted to virtual pixels. */
    internal fun wheel(x: Float, y: Float, dx: Double, dy: Double) {
        val factor = 1 / max(screen.scale, 1.0)
        send(SZInputEvent.Wheel(virtualPoint(x, y), dx * factor, dy * factor))
    }

    // Accessibility

    /** What screen readers should see: everything at or above the modal layer of the last frame. */
    val visibleAccessibilityItems: List<SZAccessibilityItem>
        get() {
            val modal = ui.modalLayer
            return ui.accessibilityItems.filter { it.layer >= modal }
        }

    /** Taps the center of `rect`, for accessibility activation. */
    internal fun tap(rect: SZRect) {
        val center = SZPoint(rect.x + rect.width / 2, rect.y + rect.height / 2)
        send(SZInputEvent.PointerDown(center, SZPointerKind.TOUCH))
        send(SZInputEvent.PointerUp(center, SZPointerKind.TOUCH))
    }

    /** Nudges an adjustable control with wheel steps. */
    internal fun adjust(rect: SZRect, direction: Double) {
        val center = SZPoint(rect.x + rect.width / 2, rect.y + rect.height / 2)
        send(SZInputEvent.Wheel(center, 6 * direction, -6 * direction))
    }
}
