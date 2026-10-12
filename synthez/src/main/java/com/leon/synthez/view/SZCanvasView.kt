package com.leon.synthez.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.ViewConfigurationCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.leon.synthez.core.SZAccessibilityItem
import com.leon.synthez.core.SZAccessibilityTraits
import com.leon.synthez.core.SZCRTSettings
import com.leon.synthez.core.SZColor
import com.leon.synthez.core.SZContext
import com.leon.synthez.core.SZFrameScheduler
import com.leon.synthez.core.SZInputEvent
import com.leon.synthez.core.SZKeyCode
import com.leon.synthez.core.SZModifiers
import com.leon.synthez.core.SZPalette
import com.leon.synthez.core.SZPointerKind
import com.leon.synthez.core.SZRect
import com.leon.synthez.core.SZResolution
import com.leon.synthez.core.SZScreen
import com.leon.synthez.core.SZTextInputRequest
import com.leon.synthez.core.SZUI
import kotlin.math.roundToInt

/**
 * A view that runs an immediate-mode UI and shows it as pixel art.
 *
 * ```kotlin
 * val canvas = SZCanvasView(context, SZTheme.synthPalette(), resolution = SZResolution.Fixed(640, 400))
 * canvas.body = {
 *     if (button("PLAY").clicked) player.play()
 * }
 * ```
 *
 * The canvas only works while it has to. A Choreographer callback ticks
 * while there is input, an animation in flight, a change waiting to be
 * drawn, or CRT afterglow fading; otherwise it stops and the view costs
 * nothing. Call [invalidateContent] when state the body reads changes from
 * outside, and forward the host's `onPause`/`onResume` to the view.
 *
 * Text widgets get the soft keyboard through an input connection, and every
 * interactive widget is a virtual accessibility node, which also makes the
 * UI scriptable from UI tests.
 */
@SuppressLint("ViewConstructor")
open class SZCanvasView(
    context: Context,
    palette: SZPalette,
    background: SZColor = 0,
    resolution: SZResolution = SZResolution.PixelScale(3.0),
    scheduler: SZFrameScheduler = SZFrameScheduler(),
) : GLSurfaceView(context) {
    private val presenter = SZGLPresenter()

    /** The platform-independent part of the canvas. */
    val engine = SZCanvasEngine(presenter, ::requestRender, palette, background, resolution, scheduler)

    private val accessibility = CanvasAccessibility()
    private val inputMethods = context.getSystemService(InputMethodManager::class.java)
    private var textInput: SZTextInputRequest? = null
    private var primaryPointer = -1

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.RGBA_8888)
        preserveEGLContextOnPause = true
        setRenderer(presenter)
        renderMode = RENDERMODE_WHEN_DIRTY
        isFocusable = true
        isFocusableInTouchMode = true
        // The canvas takes focus for the keyboard; the system's focus tint would lift every color.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        engine.onFrame = { result ->
            updateKeyboard(result.textInput)
            accessibility.onFrame()
        }
    }

    // Forwarding

    val ui: SZUI get() = engine.ui

    var body: (SZContext.() -> Unit)?
        get() = engine.body
        set(value) {
            engine.body = value
        }

    var resolution: SZResolution
        get() = engine.resolution
        set(value) {
            engine.resolution = value
        }

    var scheduler: SZFrameScheduler
        get() = engine.scheduler
        set(value) {
            engine.scheduler = value
        }

    var crt: SZCRTSettings
        get() = engine.crt
        set(value) {
            engine.crt = value
        }

    val screen: SZScreen get() = engine.screen

    /** Draws a new frame at the next refresh. */
    fun invalidateContent() = engine.invalidate()

    fun send(event: SZInputEvent) = engine.send(event)

    /** The view rectangle, in pixels, of a virtual rectangle. */
    fun viewRect(rect: SZRect) = engine.viewRect(rect)

    // Lifecycle and layout

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        engine.attach()
    }

    override fun onDetachedFromWindow() {
        engine.detach()
        if (textInput != null) inputMethods?.hideSoftInputFromWindow(windowToken, 0)
        textInput = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        engine.layout(w, h, resources.displayMetrics.density)
    }

    // Touch, mouse and wheel

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val kind = if (event.getToolType(index) == MotionEvent.TOOL_TYPE_MOUSE) SZPointerKind.MOUSE else SZPointerKind.TOUCH
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                primaryPointer = event.getPointerId(0)
                // The canvas scrolls its own content; keep parents from stealing the drag.
                parent?.requestDisallowInterceptTouchEvent(true)
                engine.send(SZInputEvent.PointerDown(engine.virtualPoint(event.getX(0), event.getY(0)), kind))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(primaryPointer)
                if (i < 0) return true
                // Historical samples keep fast drags smooth and their velocity right.
                for (h in 0 until event.historySize) {
                    engine.send(SZInputEvent.PointerMove(engine.virtualPoint(event.getHistoricalX(i, h), event.getHistoricalY(i, h)), kind))
                }
                engine.send(SZInputEvent.PointerMove(engine.virtualPoint(event.getX(i), event.getY(i)), kind))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(index) == primaryPointer) {
                    engine.send(SZInputEvent.PointerUp(engine.virtualPoint(event.getX(index), event.getY(index)), kind))
                    primaryPointer = -1
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (primaryPointer != -1) engine.send(SZInputEvent.PointerCancel)
                primaryPointer = -1
            }
        }
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE ->
                engine.send(SZInputEvent.PointerMove(engine.virtualPoint(event.x, event.y), SZPointerKind.MOUSE))
            MotionEvent.ACTION_HOVER_EXIT -> engine.send(SZInputEvent.PointerExit)
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val config = ViewConfiguration.get(context)
            val dx = event.getAxisValue(MotionEvent.AXIS_HSCROLL) * ViewConfigurationCompat.getScaledHorizontalScrollFactor(config, context)
            val dy = -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * ViewConfigurationCompat.getScaledVerticalScrollFactor(config, context)
            engine.wheel(event.x, event.y, dx.toDouble(), dy.toDouble())
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // Keyboard

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val modifiers = modifiers(event)
        val code: SZKeyCode? = when (keyCode) {
            KeyEvent.KEYCODE_DEL -> SZKeyCode.Backspace
            KeyEvent.KEYCODE_FORWARD_DEL -> SZKeyCode.DeleteForward
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> SZKeyCode.Enter
            KeyEvent.KEYCODE_ESCAPE -> SZKeyCode.Escape
            KeyEvent.KEYCODE_TAB -> SZKeyCode.Tab
            KeyEvent.KEYCODE_DPAD_LEFT -> SZKeyCode.Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> SZKeyCode.Right
            KeyEvent.KEYCODE_DPAD_UP -> SZKeyCode.Up
            KeyEvent.KEYCODE_DPAD_DOWN -> SZKeyCode.Down
            KeyEvent.KEYCODE_MOVE_HOME -> SZKeyCode.Home
            KeyEvent.KEYCODE_MOVE_END -> SZKeyCode.End
            else -> null
        }
        if (code != null) {
            engine.send(SZInputEvent.Key(code, modifiers))
            return true
        }
        if (modifiers and SZModifiers.COMMAND != 0) {
            val ch = event.getUnicodeChar(0)
            if (ch != 0) {
                engine.send(SZInputEvent.Key(SZKeyCode.Character(Character.toLowerCase(ch)), modifiers))
                return true
            }
        }
        val ch = event.unicodeChar
        if (ch != 0 && !Character.isISOControl(ch)) {
            engine.send(SZInputEvent.Text(String(Character.toChars(ch))))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Ctrl and Meta both act as the shortcut modifier. */
    private fun modifiers(event: KeyEvent): Int {
        var m = SZModifiers.NONE
        if (event.isShiftPressed) m = m or SZModifiers.SHIFT
        if (event.isCtrlPressed || event.isMetaPressed) m = m or SZModifiers.COMMAND
        if (event.isAltPressed) m = m or SZModifiers.OPTION
        return m
    }

    override fun onCheckIsTextEditor(): Boolean = textInput != null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val request = textInput ?: return null
        outAttrs.inputType = if (request.numeric) {
            InputType.TYPE_CLASS_NUMBER
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return CanvasInputConnection()
    }

    private fun updateKeyboard(request: SZTextInputRequest?) {
        val previous = textInput
        textInput = request
        val imm = inputMethods ?: return
        if (request != null) {
            if (previous == null || previous.numeric != request.numeric) {
                if (!hasFocus()) requestFocus()
                imm.restartInput(this)
                post { if (textInput != null) imm.showSoftInput(this, 0) }
            }
        } else if (previous != null) {
            imm.hideSoftInputFromWindow(windowToken, 0)
            imm.restartInput(this)
        }
    }

    /**
     * Turns what the IME commits or composes into text and key events. The
     * field draws no marked text, so composing text is typed as it goes and
     * erased again when the IME replaces it.
     */
    private inner class CanvasInputConnection : BaseInputConnection(this@SZCanvasView, false) {
        private var composing = 0

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            composing = 0
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            composing = text.toString().replace("\n", "").let { it.codePointCount(0, it.length) }
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = 0
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { engine.send(SZInputEvent.Key(SZKeyCode.Backspace)) }
            repeat(afterLength) { engine.send(SZInputEvent.Key(SZKeyCode.DeleteForward)) }
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            deleteSurroundingText(beforeLength, afterLength)

        override fun performEditorAction(actionCode: Int): Boolean {
            engine.send(SZInputEvent.Key(SZKeyCode.Enter))
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""

        private fun replaceComposing(text: String) {
            repeat(composing) { engine.send(SZInputEvent.Key(SZKeyCode.Backspace)) }
            val typed = text.replace("\n", "")
            if (typed.isNotEmpty()) engine.send(SZInputEvent.Text(typed))
            if ('\n' in text) engine.send(SZInputEvent.Key(SZKeyCode.Enter))
        }
    }

    // Accessibility

    /** Exposes every interactive widget as a virtual node, identified by its widget ID. */
    private inner class CanvasAccessibility : ExploreByTouchHelper(this@SZCanvasView) {
        private val manager = context.getSystemService(AccessibilityManager::class.java)
        private var lastItems: List<SZAccessibilityItem> = emptyList()

        fun onFrame() {
            if (manager?.isEnabled != true) return
            val items = engine.visibleAccessibilityItems
            if (items != lastItems) {
                lastItems = items
                invalidateRoot()
            }
        }

        private fun nodeID(item: SZAccessibilityItem): Int = (item.id.raw ushr 33).toInt()

        private fun find(virtualViewId: Int): SZAccessibilityItem? =
            engine.visibleAccessibilityItems.lastOrNull { nodeID(it) == virtualViewId }

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val p = engine.virtualPoint(x, y)
            var best: SZAccessibilityItem? = null
            for (item in engine.visibleAccessibilityItems) {
                if (item.rect.contains(p) && (best == null || item.layer >= best.layer)) best = item
            }
            return best?.let(::nodeID) ?: INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            val seen = HashSet<Int>()
            for (item in engine.visibleAccessibilityItems) {
                val id = nodeID(item)
                if (seen.add(id)) virtualViewIds.add(id)
            }
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val item = find(virtualViewId)
            if (item == null) {
                node.contentDescription = ""
                node.setBoundsInParent(Rect())
                return
            }
            val traits = item.traits
            fun has(flag: Int) = traits and flag != 0
            node.contentDescription = item.label
            item.value?.let { node.stateDescription = it }
            val r = engine.viewRect(item.rect)
            node.setBoundsInParent(Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()))
            node.className = when {
                has(SZAccessibilityTraits.TEXT_FIELD) -> "android.widget.EditText"
                has(SZAccessibilityTraits.ADJUSTABLE) -> "android.widget.SeekBar"
                has(SZAccessibilityTraits.BUTTON) -> "android.widget.Button"
                else -> "android.widget.TextView"
            }
            node.isEnabled = !has(SZAccessibilityTraits.NOT_ENABLED)
            node.isSelected = has(SZAccessibilityTraits.SELECTED)
            node.isHeading = has(SZAccessibilityTraits.HEADER)
            node.isFocusable = true
            if (has(SZAccessibilityTraits.BUTTON) || has(SZAccessibilityTraits.TEXT_FIELD)) {
                node.isClickable = true
                node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            }
            if (has(SZAccessibilityTraits.ADJUSTABLE)) {
                node.isScrollable = true
                node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
                node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD)
            }
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val item = find(virtualViewId) ?: return false
            return when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> {
                    engine.tap(item.rect)
                    true
                }
                AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD -> {
                    engine.adjust(item.rect, 1.0)
                    true
                }
                AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD -> {
                    engine.adjust(item.rect, -1.0)
                    true
                }
                else -> false
            }
        }
    }
}
