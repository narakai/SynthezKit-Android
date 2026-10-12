package com.leon.synthez.core

/**
 * Palette-indexed pixels, row by row. Pixels set to [TRANSPARENT] are
 * skipped when drawn. Images compare by identity, so a cached image redraws
 * as "the same" frame.
 */
class SZImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width >= 0 && height >= 0 && pixels.size == width * height) { "pixels must hold width × height indices" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    companion object {
        /** The index left undrawn. Palettes for images keep it unused (at most 255 colors). */
        const val TRANSPARENT = 255

        /**
         * Maps `0xAARRGGBB` pixels to the nearest colors of `palette`, in OKLab,
         * with 4×4 ordered dithering on lightness so gradients become patterns
         * rather than bands.
         *
         * @param colors The palette indices the image may use, or null for all.
         * @param matte Pixels are composited over this `0xRRGGBB` color first,
         *   or, when null, those under half opacity become [TRANSPARENT].
         * @param dither How far the dither pattern pushes lightness (OKLab L).
         *   The default suits palettes whose ramps step about 0.06 apart.
         */
        fun quantize(
            width: Int,
            height: Int,
            argb: IntArray,
            palette: SZPalette,
            colors: IntArray? = null,
            matte: Int? = null,
            dither: Float = 0.06f,
        ): SZImage {
            require(argb.size >= width * height)
            val out = ByteArray(width * height)
            val lab = FloatArray(3)
            // Photos repeat colors a lot; remember the nearest color per (rgb, threshold).
            val memo = HashMap<Long, Byte>()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    var c = argb[i]
                    val alpha = c ushr 24
                    if (matte == null) {
                        if (alpha < 128) {
                            out[i] = TRANSPARENT.toByte()
                            continue
                        }
                    } else if (alpha < 255) {
                        c = over(c, alpha, matte)
                    }
                    val level = SZPatterns.BAYER[(y and 3) * 4 + (x and 3)]
                    val key = ((c and 0xFFFFFF).toLong() shl 4) or level.toLong()
                    out[i] = memo.getOrPut(key) {
                        SZPalette.oklab((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, lab, 0)
                        val offset = ((level + 0.5f) / 16f - 0.5f) * dither
                        palette.nearest(lab[0] + offset, lab[1], lab[2], colors).toByte()
                    }
                }
            }
            return SZImage(width, height, out)
        }

        private fun over(c: Int, alpha: Int, matte: Int): Int {
            fun mix(shift: Int): Int {
                val top = (c shr shift) and 0xFF
                val bottom = (matte shr shift) and 0xFF
                return (top * alpha + bottom * (255 - alpha) + 127) / 255
            }
            return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
    }
}
