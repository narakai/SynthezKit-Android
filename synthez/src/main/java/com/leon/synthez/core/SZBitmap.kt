package com.leon.synthez.core

/**
 * A 1-bit image up to 64 pixels wide: icons and glyphs.
 *
 * Each row is one `Long`; bit `x` set means the pixel at column `x` is on.
 */
class SZBitmap(val width: Int, val rows: LongArray) {
    val height: Int get() = rows.size

    init {
        require(width in 0..64) { "SZBitmap is at most 64 pixels wide" }
    }

    /**
     * Parses rows such as `".##."` where `#` (or any character but `.` and
     * space) is on. The widest row sets the width.
     */
    constructor(vararg art: String) : this(art.maxOfOrNull { it.length } ?: 0, LongArray(art.size) { parseRow(art[it]) })

    override fun equals(other: Any?): Boolean =
        this === other || other is SZBitmap && width == other.width && rows.contentEquals(other.rows)

    override fun hashCode(): Int = 31 * width + rows.contentHashCode()

    companion object {
        fun parse(art: List<String>) = SZBitmap(*art.toTypedArray())

        private fun parseRow(line: String): Long {
            var bits = 0L
            for (x in line.indices) {
                val ch = line[x]
                if (ch != '.' && ch != ' ') bits = bits or (1L shl x)
            }
            return bits
        }
    }
}

/**
 * A 4×4 ordered-dither mask anchored to screen coordinates, so neighbouring
 * fills with the same pattern line up. Bit `(y & 3) * 4 + (x & 3)` set means
 * the pixel is drawn.
 */
typealias SZPattern = Int

object SZPatterns {
    const val SOLID: SZPattern = 0xFFFF
    const val CLEAR: SZPattern = 0

    /** The 4×4 Bayer threshold matrix. */
    val BAYER = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5,
    )

    /** The first `level` (0..16) cells of the Bayer order. Levels nest, so a fade that steps through them never flickers. */
    fun dither(level: Int): SZPattern {
        val l = level.coerceIn(0, 16)
        var pattern = 0
        for (i in 0 until 16) {
            if (BAYER[i] < l) pattern = pattern or (1 shl i)
        }
        return pattern
    }

    /** [dither] by coverage, 0..1. */
    fun shade(amount: Double): SZPattern = dither((amount.coerceIn(0.0, 1.0) * 16).roundToIntAway())

    /** A 50% checkerboard. */
    val HALF: SZPattern = dither(8)

    fun covers(pattern: SZPattern, x: Int, y: Int): Boolean = pattern and (1 shl (((y and 3) shl 2) or (x and 3))) != 0
}
