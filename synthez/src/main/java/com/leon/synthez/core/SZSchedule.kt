package com.leon.synthez.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The shadow mask a CRT draws its pixels through. */
enum class SZCRTMask {
    NONE,

    /** Vertical RGB stripes. */
    APERTURE,

    /** Stripes broken every few rows, staggered between triads. */
    SLOT,

    /** RGB dots offset on alternate rows. */
    SHADOW,
}

/**
 * Parameters for the CRT post-process. Everything is modeled in linear
 * light, so brighter content glows more without picking what glows.
 */
data class SZCRTSettings(
    val isEnabled: Boolean = false,
    /** How dark the gaps between scanlines get, 0..1. */
    val scanlines: Double = 0.65,
    /** Beam width (in virtual rows) for dark content. */
    val beamDim: Double = 0.28,
    /** Beam width for bright content: bright rows swell and fill the gaps. */
    val beamBright: Double = 0.52,
    val mask: SZCRTMask = SZCRTMask.APERTURE,
    val maskStrength: Double = 0.3,
    /** Glow around bright content. */
    val halation: Double = 0.3,
    /** How far the glow spreads, in halving steps (1..6). */
    val spread: Int = 4,
    val vignette: Double = 0.3,
    /** Phosphor decay time in seconds for the red channel; green and blue fade faster. */
    val persistence: Double = 0.04,
    val brightness: Double = 1.2,
    /** Light reflected off the glass. */
    val ambient: Double = 0.002,
) {
    /** How long after the last change frames must keep coming for the afterglow to fade below a thousandth. */
    val afterglow: Double get() = if (isEnabled) persistence * 7 else 0.0
}

/**
 * Decides whether a display refresh needs a frame, so an idle canvas costs
 * nothing and its frame loop can pause.
 */
class SZFrameScheduler(
    var mode: Mode = Mode.AUTO,
    /** Keep drawing this long after the last change, for effects that fade on their own such as CRT afterglow. */
    var settleDuration: Double = 0.0,
) {
    enum class Mode {
        /** Draw only when something changed, something moves, or input arrived. */
        AUTO,

        /** Draw on every refresh. */
        ALWAYS,
    }

    private var isDirty = true
    private var settleUntil = Double.NEGATIVE_INFINITY

    /** A frame was asked for at this later time. */
    var wakeAt: Double? = null
        private set

    val hasPendingWake: Boolean get() = wakeAt != null

    /** Something outside the UI changed (size, palette, app state). */
    fun invalidate() {
        isDirty = true
    }

    /** Whether the refresh at `time` should run a frame. */
    fun isDue(time: Double, hasInput: Boolean): Boolean = when (mode) {
        Mode.ALWAYS -> true
        Mode.AUTO -> isDirty || hasInput || time < settleUntil || wakeAt?.let { time >= it } == true
    }

    /** Records a frame; returns whether another refresh will be needed, so the host can pause its frame loop when it is not. */
    fun didDraw(time: Double, result: SZFrameResult): Boolean {
        isDirty = result.needsAnotherFrame
        wakeAt = result.wakeAt
        if (result.changed) settleUntil = time + settleDuration
        return mode == Mode.ALWAYS || isDirty || time < settleUntil || wakeAt != null
    }
}

/** How virtual pixels map onto a view. */
sealed class SZResolution {
    /** A fixed virtual size, scaled to fit and letterboxed. */
    data class Fixed(val width: Int, val height: Int) : SZResolution()

    /** Each virtual pixel is `scale` device pixels; the virtual size follows the view. */
    data class PixelScale(val scale: Double) : SZResolution()

    /** Each virtual pixel is `scale` dp, so pixels look the same size on every density; the virtual size follows the view. */
    data class PointScale(val scale: Double) : SZResolution()
}

/** The virtual screen for a view of a given device-pixel size. */
data class SZScreen(
    /** Virtual pixels. */
    val size: SZSize,
    /** Device pixels per virtual pixel, possibly fractional. */
    val scale: Double,
    /** Where the scaled screen lands, in device pixels from the top left. */
    val viewportX: Double,
    val viewportY: Double,
    val viewportWidth: Double,
    val viewportHeight: Double,
) {
    /** The virtual pixel under a device-pixel point. */
    fun virtualPoint(deviceX: Double, deviceY: Double) =
        SZPoint(floor((deviceX - viewportX) / scale).toInt(), floor((deviceY - viewportY) / scale).toInt())

    companion object {
        /** @param deviceScale Device pixels per dp, for [SZResolution.PointScale]. */
        fun of(resolution: SZResolution, deviceWidth: Double, deviceHeight: Double, deviceScale: Double = 1.0): SZScreen {
            val dw = max(deviceWidth, 1.0)
            val dh = max(deviceHeight, 1.0)
            val resolved = if (resolution is SZResolution.PointScale) SZResolution.PixelScale(resolution.scale * max(deviceScale, 1.0)) else resolution
            val size: SZSize
            val scale: Double
            when (resolved) {
                is SZResolution.Fixed -> {
                    val w = max(resolved.width, 1)
                    val h = max(resolved.height, 1)
                    size = SZSize(w, h)
                    scale = min(dw / w, dh / h)
                }
                is SZResolution.PixelScale -> {
                    val s = max(resolved.scale, 1.0)
                    size = SZSize(max((dw / s).toInt(), 1), max((dh / s).toInt(), 1))
                    scale = s
                }
                is SZResolution.PointScale -> error("Resolved to PixelScale above")
            }
            val vw = size.width * scale
            val vh = size.height * scale
            return SZScreen(size, scale, floor((dw - vw) / 2), floor((dh - vh) / 2), vw, vh)
        }
    }
}
