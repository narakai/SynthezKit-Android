package com.leon.synthez.core

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cbrt
import kotlin.math.pow

/** An 8-bit sRGB color with alpha. */
data class SZRGBA(val r: Int, val g: Int, val b: Int, val a: Int = 255) {
    val argb: Int get() = (a shl 24) or (r shl 16) or (g shl 8) or b

    companion object {
        /** `0xRRGGBB`. */
        fun hex(hex: Int) = SZRGBA((hex shr 16) and 0xFF, (hex shr 8) and 0xFF, hex and 0xFF)
    }
}

/**
 * An index into an [SZPalette], 0..255. Everything SynthezKit draws is a
 * palette index; the presenter turns indices into colors on the GPU.
 */
typealias SZColor = Int

/**
 * Up to 256 colors, plus the color maps derived from them.
 *
 * Fades, shadows and dimming never blend: they map every palette color to the
 * nearest palette color of the blended result, measured in OKLab, so the
 * screen keeps the palette's look. A palette whose colors form ramps (dark to
 * light) gives smooth fades.
 */
class SZPalette(colors: List<SZRGBA>) {
    val colors: List<SZRGBA> = colors.toList()

    /** Bumped whenever a palette is made, so presenters upload only then. */
    val version: Long = versionCounter.incrementAndGet()

    private val lab = FloatArray(colors.size * 3)
    private val mixCache = HashMap<Int, SZColorMap>()
    internal val filterCache = HashMap<Int, SZFilterMaps>()
    private var nextMapID = 1

    init {
        require(colors.isNotEmpty() && colors.size <= 256) { "A palette holds 1..256 colors" }
        colors.forEachIndexed { i, c -> oklab(c, lab, i * 3) }
    }

    val count: Int get() = colors.size

    /** The palette color closest to the OKLab color `(l, a, b)`, among `colors` when given. */
    fun nearest(l: Float, a: Float, b: Float, colors: IntArray? = null): SZColor {
        var best = colors?.firstOrNull() ?: 0
        var bestDistance = Float.POSITIVE_INFINITY
        val n = colors?.size ?: count
        for (k in 0 until n) {
            val i = colors?.get(k) ?: k
            val dl = lab[i * 3] - l
            val da = lab[i * 3 + 1] - a
            val db = lab[i * 3 + 2] - b
            val distance = dl * dl + da * da + db * db
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    /**
     * The map that moves every color `amount` (0..1) of the way toward `target`.
     *
     * The amount is quantized to 1/32 steps and each step is built once.
     */
    fun mixMap(target: SZColor, amount: Double): SZColorMap {
        val level = (amount.coerceIn(0.0, 1.0) * 32).roundToIntAway()
        val cacheKey = (target shl 8) or level
        mixCache[cacheKey]?.let { return it }
        val gl = lab[target * 3]
        val ga = lab[target * 3 + 1]
        val gb = lab[target * 3 + 2]
        val f = level / 32f
        val table = ByteArray(256)
        for (i in 0 until 256) {
            table[i] = if (i >= count || level == 0) {
                i.toByte()
            } else {
                val l = lab[i * 3]
                val a = lab[i * 3 + 1]
                val b = lab[i * 3 + 2]
                nearest(l + (gl - l) * f, a + (ga - a) * f, b + (gb - b) * f).toByte()
            }
        }
        val map = SZColorMap(nextMapID++, table)
        mixCache[cacheKey] = map
        return map
    }

    /** Maps toward `target` in 32 steps, built once per target. */
    fun filterMaps(target: SZColor): SZFilterMaps =
        filterCache.getOrPut(target) { SZFilterMaps((0..32).map { mixMap(target, it / 32.0) }) }

    internal companion object {
        private val versionCounter = AtomicLong(0)

        private fun oklab(c: SZRGBA, out: FloatArray, offset: Int) = oklab(c.r, c.g, c.b, out, offset)

        /** Writes the OKLab `(L, a, b)` of an sRGB color to `out` at `offset`. */
        fun oklab(red: Int, green: Int, blue: Int, out: FloatArray, offset: Int) {
            fun linear(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            val r = linear(red)
            val g = linear(green)
            val b = linear(blue)
            val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            out[offset] = (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s).toFloat()
            out[offset + 1] = (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s).toFloat()
            out[offset + 2] = (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s).toFloat()
        }
    }
}

/** A 256-entry index-to-index lookup, applied to pixels already drawn. Compared by identity. */
class SZColorMap(val id: Int, val table: ByteArray) {
    init {
        require(table.size == 256)
    }

    /** The color `index` maps to. */
    operator fun get(index: Int): Int = table[index].toInt() and 0xFF
}
