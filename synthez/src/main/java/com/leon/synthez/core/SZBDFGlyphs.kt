package com.leon.synthez.core

import kotlin.math.max

/**
 * Glyphs read from an X11 BDF bitmap font, such as k8x12.
 *
 * Loading scans the file once and remembers where each character's block
 * starts; a glyph is decoded the first time it is asked for, and the
 * [SZBitmapFont] that owns this source keeps it from then on.
 *
 * Each glyph is placed in the font's cell: [ascent] rows above the baseline
 * and [descent] below. BDF advances usually include a blank column of
 * spacing; `trim` takes that many columns off each advance (never cutting
 * into ink), so the owning font's own spacing replaces it.
 *
 * @param data The BDF file, which is ASCII.
 */
class SZBDFGlyphs(private val data: ByteArray, private val trim: Int = 1) : SZGlyphSource {
    override val ascent: Int
    override val descent: Int

    /** Code points, sorted, and the offsets of their blocks. */
    private val codes: IntArray
    private val offsets: IntArray

    val count: Int get() = codes.size

    init {
        var fontAscent = -1
        var fontDescent = -1
        var boxHeight = 0
        var boxBottom = 0
        var blockStart = -1
        val found = ArrayList<Long>(8192)
        var pos = 0
        while (pos < data.size) {
            val end = lineEnd(pos)
            when {
                startsWith(pos, end, "STARTCHAR") -> blockStart = pos
                startsWith(pos, end, "ENCODING ") -> {
                    val code = ints(pos + 9, end).firstOrNull() ?: -1
                    if (blockStart >= 0 && code >= 0) found.add((code.toLong() shl 32) or blockStart.toLong())
                }
                startsWith(pos, end, "FONT_ASCENT ") -> fontAscent = ints(pos + 12, end).firstOrNull() ?: -1
                startsWith(pos, end, "FONT_DESCENT ") -> fontDescent = ints(pos + 13, end).firstOrNull() ?: -1
                startsWith(pos, end, "FONTBOUNDINGBOX ") -> {
                    val box = ints(pos + 16, end)
                    if (box.size == 4) {
                        boxHeight = box[1]
                        boxBottom = box[3]
                    }
                }
            }
            pos = end + 1
        }
        // FONT_ASCENT and FONT_DESCENT are optional; the bounding box has the same answer.
        ascent = if (fontAscent >= 0) fontAscent else max(boxHeight + boxBottom, 0)
        descent = if (fontDescent >= 0) fontDescent else max(-boxBottom, 0)
        found.sort()
        codes = IntArray(found.size) { (found[it] ushr 32).toInt() }
        offsets = IntArray(found.size) { found[it].toInt() }
    }

    override fun glyph(codePoint: Int): SZBitmap? {
        val i = codes.binarySearch(codePoint)
        if (i < 0) return null
        var pos = offsets[i]
        var advance = 0
        var w = 0
        var h = 0
        var xOffset = 0
        var yOffset = 0
        val height = ascent + descent
        val rows = LongArray(height)
        var ink = 0
        var row = -1
        while (pos < data.size) {
            val end = lineEnd(pos)
            if (row >= 0) {
                if (startsWith(pos, end, "ENDCHAR")) break
                if (row < h) {
                    val digits = hexDigits(pos, end)
                    val bits = hex(pos, pos + digits)
                    val rowBits = digits * 4
                    val y = ascent - (yOffset + h) + row
                    if (y in 0 until height) {
                        for (x in 0 until w) {
                            if ((bits ushr (rowBits - 1 - x)) and 1L != 0L) {
                                val column = xOffset + x
                                if (column in 0 until 64) {
                                    rows[y] = rows[y] or (1L shl column)
                                    ink = max(ink, column + 1)
                                }
                            }
                        }
                    }
                }
                row++
            } else {
                when {
                    startsWith(pos, end, "DWIDTH ") -> advance = ints(pos + 7, end).firstOrNull() ?: 0
                    startsWith(pos, end, "BBX ") -> {
                        val box = ints(pos + 4, end)
                        if (box.size == 4) {
                            w = box[0]
                            h = box[1]
                            xOffset = box[2]
                            yOffset = box[3]
                        }
                    }
                    startsWith(pos, end, "BITMAP") -> row = 0
                    startsWith(pos, end, "ENDCHAR") -> break
                }
            }
            pos = end + 1
        }
        val width = max(advance - trim, ink).coerceIn(0, 64)
        return SZBitmap(width, rows)
    }

    // Byte-level parsing; BDF is ASCII.

    private fun lineEnd(from: Int): Int {
        var i = from
        while (i < data.size && data[i] != '\n'.code.toByte()) i++
        return i
    }

    private fun startsWith(from: Int, end: Int, prefix: String): Boolean {
        if (end - from < prefix.length) return false
        for (k in prefix.indices) if (data[from + k] != prefix[k].code.toByte()) return false
        return true
    }

    /** Whitespace-separated integers in `from until end`. */
    private fun ints(from: Int, end: Int): List<Int> {
        val out = ArrayList<Int>(4)
        var i = from
        while (i < end) {
            while (i < end && (data[i] == ' '.code.toByte() || data[i] == '\t'.code.toByte() || data[i] == '\r'.code.toByte())) i++
            if (i >= end) break
            var negative = false
            if (data[i] == '-'.code.toByte()) {
                negative = true
                i++
            }
            var value = 0
            var digits = 0
            while (i < end && data[i] in '0'.code.toByte()..'9'.code.toByte()) {
                value = value * 10 + (data[i] - '0'.code.toByte())
                i++
                digits++
            }
            if (digits == 0) break
            out.add(if (negative) -value else value)
        }
        return out
    }

    /** How many hex digits start at `from`, at most 16. */
    private fun hexDigits(from: Int, end: Int): Int {
        var n = 0
        while (from + n < end && n < 16 && Character.digit(data[from + n].toInt(), 16) >= 0) n++
        return n
    }

    /** A row of hex digits; the first digit holds the leftmost pixels. */
    private fun hex(from: Int, end: Int): Long {
        var value = 0L
        var i = from
        var digits = 0
        while (i < end && digits < 16) {
            val d = when (val c = data[i].toInt()) {
                in '0'.code..'9'.code -> c - '0'.code
                in 'A'.code..'F'.code -> c - 'A'.code + 10
                in 'a'.code..'f'.code -> c - 'a'.code + 10
                else -> break
            }
            value = (value shl 4) or d.toLong()
            i++
            digits++
        }
        return value
    }
}
