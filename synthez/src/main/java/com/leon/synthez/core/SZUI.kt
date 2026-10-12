package com.leon.synthez.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What one frame produced, for the host. */
data class SZFrameResult(
    /** Something is moving or asked for another frame. */
    val needsAnotherFrame: Boolean,
    /** The draw list differs from the previous frame's, so the surface must be rasterized and presented again. */
    val changed: Boolean,
    /** A focused text widget wants the keyboard. */
    val textInput: SZTextInputRequest? = null,
    /** Draw again at this time even if nothing else happens (caret blink). */
    val wakeAt: Double? = null,
)

/**
 * A boxed piece of state that lives as long as the call that owns it keeps
 * being made. It is a class so a widget can mutate it in place.
 */
class SZState<T> internal constructor(var value: T)

/** A value read from [SZUI.environment]. */
interface SZEnvironmentKey<T> {
    val defaultValue: T
}

/**
 * Everything that lives across frames: time, pointer, focus, state and the
 * previous frame's interactive rectangles.
 *
 * Call [frame] once per display refresh that needs drawing. Widgets talk to
 * the UI through the [SZContext] the body gets. Not thread-safe: use it from
 * one thread.
 */
class SZUI(var palette: SZPalette, var background: SZColor = 0) {
    var frameNumber = 0L
        private set

    /** Seconds, on the clock the host passes in. */
    var time = 0.0
        private set

    /** Seconds since the previous frame, at most 0.1. */
    var dt = 1.0 / 60
        private set
    var screen = SZRect.ZERO
        private set

    /** The list the current (or last finished) frame drew into. */
    var drawList = SZDrawList()
        private set
    private var previousList = SZDrawList()

    // Pointer.
    var pointer: SZPoint? = null
        private set
    var pointerKind = SZPointerKind.TOUCH
        private set
    var isPointerDown = false
        private set

    /** Where the current press started. */
    var pressOrigin = SZPoint.ZERO
        private set

    /** Pointer velocity in virtual pixels per second, measured over the last moments of the drag; kept after release for flings. */
    var pointerVelocity = SZVector.ZERO
        private set
    private var dragOrigin = SZPoint.ZERO
    private var pressTime = 0.0

    private class Sample(val time: Double, val point: SZPoint)

    private val samples = ArrayList<Sample>()
    internal var active: SZID? = null
    internal var activeSense = 0
    internal var focus: SZID? = null
    internal var hoverTarget: SZID? = null
    internal var pressedID: SZID? = null
    internal var releasedID: SZID? = null
    internal var clickedID: SZID? = null
    internal var pressCount = 0
    private var lastPressTime = Double.NEGATIVE_INFINITY
    private var lastPressPoint = SZPoint.ZERO

    internal class WheelTarget(val id: SZID, var dx: Double, var dy: Double)

    internal val wheelTargets = ArrayList<WheelTarget>()

    /** Taps closer together than this, in seconds and virtual pixels, count up [SZInteraction.presses]. */
    var multiPressInterval = 0.4
    var multiPressDistance = 8

    // Keyboard.
    private class KeyEntry(val event: SZInputEvent, var consumed: Boolean)

    private val keyEvents = ArrayList<KeyEntry>()
    internal var textInput: SZTextInputRequest? = null

    // Hints: what the pointer is on, for status lines and tooltips.
    var hint: String? = null
        private set
    var hintAnchor = SZRect.ZERO
        private set
    private var hintOwner: SZID? = null
    private var hintSince = 0.0

    private class PendingHint(val id: SZID, val text: String, val rect: SZRect)

    private var pendingHint: PendingHint? = null

    // Accessibility.
    private var accessibilityStore = ArrayList<SZAccessibilityItem>()
    private var collectingAccessibility = ArrayList<SZAccessibilityItem>()

    /** What the last frame described to screen readers and UI tests. */
    val accessibilityItems: List<SZAccessibilityItem> get() = accessibilityStore

    /** The lowest layer screen readers see; modal overlays raise it. */
    var modalLayer = 0
        private set
    internal var pendingModalLayer = 0

    // Routing.
    internal var hits = ArrayList<SZHit>()
    private var previousHits = ArrayList<SZHit>()

    // State.
    private class StateEntry(var frame: Long, val box: SZState<*>)

    internal class AnimationEntry(var frame: Long, var value: Double)

    private val states = HashMap<SZID, StateEntry>()
    internal val animations = HashMap<SZID, AnimationEntry>()
    private var touchedStates = 0
    internal var touchedAnimations = 0
    private val environmentValues = HashMap<SZEnvironmentKey<*>, Any?>()

    /** Wrapped text, kept while it is drawn. */
    val textLayouts = SZTextLayoutCache()

    internal var wantsFrame = false

    /** The previous frame asked for another, so time since it is motion time. */
    private var wasAnimating = false
    internal var wakeAt: Double? = null
    var isInFrame = false
        private set

    // Frame

    /** Runs `body` for one frame and records what it draws into [drawList]. */
    fun frame(time: Double, size: SZSize, events: List<SZInputEvent>, body: SZContext.() -> Unit): SZFrameResult {
        check(!isInFrame) { "SZUI.frame is not reentrant" }
        isInFrame = true
        try {
            frameNumber++
            // After an idle stretch the gap since the last frame is not motion
            // time: an animation starting now must begin from its first step.
            dt = if (frameNumber == 1L || !wasAnimating) 1.0 / 60 else (time - this.time).coerceIn(0.0, 0.1)
            this.time = time
            screen = SZRect(0, 0, size.width, size.height)
            wantsFrame = false
            wakeAt = null
            touchedStates = 0
            touchedAnimations = 0
            textInput = null
            pendingHint = null
            pendingModalLayer = 0
            collectingAccessibility.clear()

            val swapHits = hits
            hits = previousHits
            previousHits = swapHits
            hits.clear()
            take(events)

            val swapList = drawList
            drawList = previousList
            previousList = swapList
            drawList.begin(screen, background)
            val root = SZContext(this, drawList, SZID.ROOT, SZPoint.ZERO, screen, screen, 0, 0)
            root.body()

            finish()
            wasAnimating = wantsFrame
            // Input handled this frame may have changed state the widgets drew
            // before reacting to it; one more frame shows the result.
            return SZFrameResult(
                needsAnotherFrame = wantsFrame || appliedEvents > 0 || deferredEvents.isNotEmpty() || (isPointerDown && active != null),
                changed = frameNumber == 1L || !drawList.isEqual(previousList),
                textInput = textInput,
                wakeAt = wakeAt,
            )
        } finally {
            isInFrame = false
        }
    }

    /** Asks the host for another frame after this one, for animations that do not go through [SZContext.animatedValue]. */
    fun requestFrame() {
        wantsFrame = true
    }

    /** Asks for a frame at `time`, without frames before it. */
    fun requestFrame(at: Double) {
        wakeAt = min(wakeAt ?: at, at)
    }

    private fun finish() {
        active?.let { id -> if (hits.none { it.id == id }) active = null }
        focus?.let { id -> if (hits.none { it.id == id }) focus = null }
        if (!isPointerDown && pointerKind == SZPointerKind.TOUCH) pointer = null
        pressedID = null
        releasedID = null
        clickedID = null
        wheelTargets.clear()
        keyEvents.clear()

        val pending = pendingHint
        if (pending != null) {
            if (hintOwner != pending.id) {
                hintOwner = pending.id
                hintSince = time
            }
            hint = pending.text
            hintAnchor = pending.rect
        } else {
            hint = null
            hintOwner = null
        }
        modalLayer = pendingModalLayer
        val swapItems = accessibilityStore
        accessibilityStore = collectingAccessibility
        collectingAccessibility = swapItems

        // Sweep only when something was skipped this frame.
        val frame = frameNumber
        if (touchedStates < states.size) states.values.removeAll { it.frame != frame }
        if (touchedAnimations < animations.size) animations.values.removeAll { it.frame != frame }
        textLayouts.endFrame(frameNumber)
    }

    // Input

    /** Events held back for the next frame. */
    private val deferredEvents = ArrayList<SZInputEvent>()

    /** Events applied this frame, from the caller or from the deferred queue. */
    private var appliedEvents = 0

    /**
     * Takes this frame's events, trickling them like Dear ImGui: a press and
     * its release never take effect in the same frame. A whole tap or flick
     * can arrive between two frames (a hitch, or the first frame after the
     * loop wakes); without trickling, widgets would never see the pointer
     * down, so buttons would skip their pressed look and scroll views could
     * not take a drag over. The rest waits for the next frame.
     */
    private fun take(incoming: List<SZInputEvent>) {
        appliedEvents = 0
        val events = ArrayList<SZInputEvent>(deferredEvents.size + incoming.size)
        events.addAll(deferredEvents)
        deferredEvents.clear()
        events.addAll(incoming)
        var wentDown = false
        var wentUp = false
        for ((index, event) in events.withIndex()) {
            when (event) {
                is SZInputEvent.PointerDown -> {
                    if (wentDown || wentUp) {
                        deferredEvents.addAll(events.subList(index, events.size))
                        break
                    }
                    wentDown = true
                }
                is SZInputEvent.PointerUp, SZInputEvent.PointerCancel -> {
                    if (wentDown || wentUp) {
                        deferredEvents.addAll(events.subList(index, events.size))
                        break
                    }
                    wentUp = true
                }
                else -> {}
            }
            apply(event)
            appliedEvents++
        }
        if (isPointerDown) pointerVelocity = measureVelocity()
        hoverTarget = pointer?.let { hitTest(it, SZSense.NONE) }
    }

    /** Whether events wait for the next frame. */
    val hasDeferredInput: Boolean get() = deferredEvents.isNotEmpty()

    private fun apply(event: SZInputEvent) {
        when (event) {
            is SZInputEvent.PointerDown -> {
                val p = event.point
                pointer = p
                pointerKind = event.kind
                isPointerDown = true
                pressOrigin = p
                dragOrigin = p
                pressTime = time
                samples.clear()
                samples.add(Sample(time, p))
                pointerVelocity = SZVector.ZERO
                val target = hitTest(p, SZSense.CLICK)
                active = target
                activeSense = target?.let { id -> previousHits.lastOrNull { it.id == id }?.sense } ?: 0
                pressedID = target
                if (target != null) {
                    val isRepeat = time - lastPressTime <= multiPressInterval &&
                        abs(p.x - lastPressPoint.x) <= multiPressDistance &&
                        abs(p.y - lastPressPoint.y) <= multiPressDistance
                    pressCount = if (isRepeat) pressCount + 1 else 1
                    lastPressTime = time
                    lastPressPoint = p
                }
                focus = hitTest(p, SZSense.FOCUS)
            }
            is SZInputEvent.PointerMove -> {
                pointer = event.point
                pointerKind = event.kind
                if (isPointerDown) addSample(event.point)
            }
            is SZInputEvent.PointerUp -> {
                val p = event.point
                pointer = p
                pointerKind = event.kind
                addSample(p)
                pointerVelocity = measureVelocity()
                isPointerDown = false
                active?.let { id ->
                    releasedID = id
                    if (activeSense and SZSense.DRAG != 0 || hitTest(p, SZSense.CLICK) == id) clickedID = id
                }
                active = null
            }
            SZInputEvent.PointerCancel -> {
                isPointerDown = false
                active?.let { releasedID = it }
                active = null
                pointerVelocity = SZVector.ZERO
                if (pointerKind == SZPointerKind.TOUCH) pointer = null
            }
            SZInputEvent.PointerExit -> if (!isPointerDown) pointer = null
            is SZInputEvent.Wheel -> {
                pointer = event.point
                val id = hitTest(event.point, SZSense.WHEEL) ?: return
                val existing = wheelTargets.firstOrNull { it.id == id }
                if (existing != null) {
                    existing.dx += event.dx
                    existing.dy += event.dy
                } else {
                    wheelTargets.add(WheelTarget(id, event.dx, event.dy))
                }
            }
            is SZInputEvent.Key, is SZInputEvent.Text -> keyEvents.add(KeyEntry(event, false))
        }
    }

    private fun addSample(p: SZPoint) {
        if (samples.size >= 8) samples.removeAt(0)
        samples.add(Sample(time, p))
    }

    /** Velocity over the samples of the last 0.1 s. */
    private fun measureVelocity(): SZVector {
        val last = samples.lastOrNull() ?: return SZVector.ZERO
        val first = samples.firstOrNull { last.time - it.time <= 0.1 }
        if (first == null || last.time <= first.time) {
            return if (time - last.time > 0.05) SZVector.ZERO else pointerVelocity
        }
        // A finger that stopped before lifting should not fling.
        if (time - last.time > 0.05) return SZVector.ZERO
        val dt = last.time - first.time
        return SZVector((last.point.x - first.point.x) / dt, (last.point.y - first.point.y) / dt)
    }

    /**
     * The topmost rectangle of the previous frame under `p` that has `sense`
     * (any rectangle if `sense` is empty).
     *
     * The highest layer with any rectangle under `p` wins outright, so an
     * overlay blocks everything beneath where it registered input; within a
     * layer, later rectangles are on top.
     */
    internal fun hitTest(p: SZPoint, sense: Int): SZID? {
        var topLayer = -1
        for (hit in previousHits) {
            if (hit.layer > topLayer && hit.rect.contains(p)) topLayer = hit.layer
        }
        if (topLayer < 0) return null
        for (i in previousHits.indices.reversed()) {
            val hit = previousHits[i]
            if (hit.layer == topLayer && hit.rect.contains(p) && (sense == 0 || hit.sense and sense != 0)) return hit.id
        }
        return null
    }

    internal fun interaction(id: SZID, rect: SZRect, sense: Int, layer: Int): SZInteraction {
        if (!rect.isEmpty) hits.add(SZHit(id, rect, layer, sense))
        val result = SZInteraction(id)
        val isActive = active == id
        result.held = isActive && isPointerDown
        result.hovered = hoverTarget == id && (active == null || isActive)
        if (pointerKind == SZPointerKind.TOUCH && !result.held) result.hovered = false
        val p = pointer
        if (result.held && pointerKind == SZPointerKind.TOUCH && p != null) result.hovered = rect.contains(p)
        result.pressed = pressedID == id
        result.released = releasedID == id
        result.clicked = clickedID == id
        result.presses = if (result.pressed || result.released) pressCount else 0
        result.focused = focus == id
        if (result.held && p != null) {
            result.dx = p.x - dragOrigin.x
            result.dy = p.y - dragOrigin.y
            result.heldDuration = time - pressTime
        }
        for (wheel in wheelTargets) {
            if (wheel.id == id) {
                result.wheelX = wheel.dx
                result.wheelY = wheel.dy
                break
            }
        }
        return result
    }

    /** Whether any rectangle above `layer` covers `p`, from the previous frame. */
    internal fun isCovered(p: SZPoint, layer: Int): Boolean = previousHits.any { it.layer > layer && it.rect.contains(p) }

    /** Whether `id` may take the pointer over: nothing holds it, or what holds it does not keep its drags. */
    internal fun canCapture(id: SZID): Boolean = isPointerDown && active != id && activeSense and SZSense.DRAG == 0

    /** Makes `id` the holder of the pointer from here on; the control that was pressed will not click. Movement restarts from the current point. */
    internal fun capture(id: SZID) {
        if (!isPointerDown) return
        active = id
        activeSense = SZSense.DRAG
        pointer?.let { dragOrigin = it }
    }

    /** Moves focus to `id`, or clears it. */
    internal fun moveFocus(id: SZID?) {
        focus = id
    }

    // Keyboard

    /** Takes the first unconsumed press of `key`. */
    internal fun takeKey(key: SZKeyCode, modifiers: Int): Boolean {
        for (entry in keyEvents) {
            if (entry.consumed) continue
            val event = entry.event
            if (event is SZInputEvent.Key && event.code == key && event.modifiers == modifiers) {
                entry.consumed = true
                return true
            }
        }
        return false
    }

    /** Takes every unconsumed key and text event, in order. */
    internal fun takeTextEvents(caret: SZRect, numeric: Boolean): List<SZInputEvent> {
        textInput = SZTextInputRequest(caret, numeric)
        val taken = ArrayList<SZInputEvent>()
        for (entry in keyEvents) {
            if (entry.consumed) continue
            entry.consumed = true
            taken.add(entry.event)
        }
        return taken
    }

    // Hints and accessibility

    internal fun setHint(text: String, owner: SZID, rect: SZRect) {
        pendingHint = PendingHint(owner, text, rect)
    }

    /** Seconds the current hint has been showing. */
    val hintAge: Double get() = if (hint == null) 0.0 else time - hintSince

    internal fun addAccessibility(item: SZAccessibilityItem) {
        collectingAccessibility.add(item)
    }

    internal fun raiseModalLayer(layer: Int) {
        pendingModalLayer = max(pendingModalLayer, layer)
    }

    // State

    /** The state boxed under `id`. A key must always hold the same type of value. */
    @Suppress("UNCHECKED_CAST")
    internal fun <T> state(id: SZID, initial: () -> T): SZState<T> {
        val entry = states[id]
        if (entry != null) {
            if (entry.frame != frameNumber) {
                entry.frame = frameNumber
                touchedStates++
            }
            return entry.box as SZState<T>
        }
        val box = SZState(initial())
        states[id] = StateEntry(frameNumber, box)
        touchedStates++
        return box
    }

    internal fun animatedValue(id: SZID, target: Double, easing: Double, snap: Double, from: Double?): Double {
        val entry = animations[id]
        if (entry == null) {
            val start = from ?: target
            animations[id] = AnimationEntry(frameNumber, start)
            touchedAnimations++
            if (start != target) wantsFrame = true
            return start
        }
        // A second read in the same frame must not advance it again.
        if (entry.frame == frameNumber) return entry.value
        entry.frame = frameNumber
        entry.value = SZEase.approach(entry.value, target, dt, easing, snap)
        touchedAnimations++
        if (entry.value != target) wantsFrame = true
        return entry.value
    }

    // Environment

    @Suppress("UNCHECKED_CAST")
    fun <T> environment(key: SZEnvironmentKey<T>): T =
        if (environmentValues.containsKey(key)) environmentValues[key] as T else key.defaultValue

    fun <T> setEnvironment(key: SZEnvironmentKey<T>, value: T) {
        environmentValues[key] = value
    }
}
