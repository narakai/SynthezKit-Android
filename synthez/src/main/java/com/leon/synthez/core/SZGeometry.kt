package com.leon.synthez.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A point in virtual pixels. */
data class SZPoint(val x: Int, val y: Int) {
    operator fun plus(other: SZPoint) = SZPoint(x + other.x, y + other.y)

    operator fun minus(other: SZPoint) = SZPoint(x - other.x, y - other.y)

    companion object {
        val ZERO = SZPoint(0, 0)
    }
}

/** A size in virtual pixels. */
data class SZSize(val width: Int, val height: Int) {
    companion object {
        val ZERO = SZSize(0, 0)
    }
}

/**
 * An axis-aligned rectangle in whole virtual pixels.
 *
 * The right and bottom edges are exclusive: `SZRect(0, 0, 2, 2)` covers four
 * pixels. A rectangle with a non-positive width or height is empty.
 */
data class SZRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    constructor(origin: SZPoint, size: SZSize) : this(origin.x, origin.y, size.width, size.height)

    val minX: Int get() = x
    val minY: Int get() = y
    val maxX: Int get() = x + width
    val maxY: Int get() = y + height
    val origin: SZPoint get() = SZPoint(x, y)
    val size: SZSize get() = SZSize(width, height)
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(p: SZPoint): Boolean = contains(p.x, p.y)

    fun contains(px: Int, py: Int): Boolean = px >= x && py >= y && px < maxX && py < maxY

    fun intersection(other: SZRect): SZRect {
        val x0 = max(x, other.x)
        val y0 = max(y, other.y)
        val x1 = min(maxX, other.maxX)
        val y1 = min(maxY, other.maxY)
        return SZRect(x0, y0, max(x1 - x0, 0), max(y1 - y0, 0))
    }

    fun union(other: SZRect): SZRect {
        if (isEmpty) return other
        if (other.isEmpty) return this
        val x0 = min(x, other.x)
        val y0 = min(y, other.y)
        return SZRect(x0, y0, max(maxX, other.maxX) - x0, max(maxY, other.maxY) - y0)
    }

    fun offsetBy(dx: Int, dy: Int) = SZRect(x + dx, y + dy, width, height)

    /** Shrinks the rectangle in CSS order; negative values grow it. */
    fun inset(top: Int, right: Int, bottom: Int, left: Int) =
        SZRect(x + left, y + top, max(width - left - right, 0), max(height - top - bottom, 0))

    fun inset(amount: Int) = inset(amount, amount, amount, amount)

    /** `size` centered inside this rectangle, its origin rounded toward zero. */
    fun centered(size: SZSize) = SZRect(x + (width - size.width) / 2, y + (height - size.height) / 2, size.width, size.height)

    companion object {
        val ZERO = SZRect(0, 0, 0, 0)
    }
}

enum class SZAxis { HORIZONTAL, VERTICAL }

/** Cross-axis alignment for [SZContext.place]. */
enum class SZAlignment { START, CENTER, END, STRETCH }

/** A velocity or offset in virtual pixels. */
data class SZVector(val x: Double, val y: Double) {
    companion object {
        val ZERO = SZVector(0.0, 0.0)
    }
}

/** Rounds half away from zero, like Swift's `rounded()`; Kotlin's `round` is half-even. */
internal fun Double.roundedAway(): Double {
    val f = floor(this)
    val d = this - f
    return when {
        d > 0.5 -> f + 1
        d < 0.5 -> f
        this >= 0 -> f + 1
        else -> f
    }
}

internal fun Double.roundToIntAway(): Int = roundedAway().toInt()
