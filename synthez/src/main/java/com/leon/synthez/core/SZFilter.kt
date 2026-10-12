package com.leon.synthez.core

import kotlin.math.sqrt

/**
 * How strongly a filter applies at each pixel, 0..1, evaluated at pixel
 * centers in absolute virtual pixels.
 */
sealed class SZStrengthField {
    data class Constant(val strength: Double) : SZStrengthField()

    /** `start` at `from`, `end` at `to`, clamped beyond. */
    data class Linear(val from: SZPoint, val to: SZPoint, val start: Double, val end: Double) : SZStrengthField()

    /** `start` within `inner` of `center`, `end` from `outer` on. */
    data class Radial(val center: SZPoint, val inner: Double, val outer: Double, val start: Double, val end: Double) : SZStrengthField()

    fun strength(x: Int, y: Int): Double = when (this) {
        is Constant -> strength
        is Linear -> {
            val dx = (to.x - from.x).toDouble()
            val dy = (to.y - from.y).toDouble()
            val length = dx * dx + dy * dy
            if (length <= 0) {
                end
            } else {
                val t = ((x + 0.5 - from.x) * dx + (y + 0.5 - from.y) * dy) / length
                start + (end - start) * t.coerceIn(0.0, 1.0)
            }
        }
        is Radial -> {
            val dx = x + 0.5 - center.x
            val dy = y + 0.5 - center.y
            val d = sqrt(dx * dx + dy * dy)
            val t = if (outer > inner) (d - inner) / (outer - inner) else if (d >= outer) 1.0 else 0.0
            start + (end - start) * t.coerceIn(0.0, 1.0)
        }
    }

    internal fun offsetBy(dx: Int, dy: Int): SZStrengthField = when (this) {
        is Constant -> this
        is Linear -> copy(from = SZPoint(from.x + dx, from.y + dy), to = SZPoint(to.x + dx, to.y + dy))
        is Radial -> copy(center = SZPoint(center.x + dx, center.y + dy))
    }
}

/** How a filter turns strength into pixels. */
enum class SZFilterBlend {
    /** Replaces a pixel outright where the Bayer threshold is below the strength: the area dissolves. */
    DITHER,

    /** Maps every pixel part of the way along the palette toward the target, in quantized steps. */
    STEPS,
}

/** The color maps a filter steps through, from untouched (first) to fully the target (last). Compared by identity. */
class SZFilterMaps internal constructor(val maps: List<SZColorMap>)
