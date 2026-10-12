package com.leon.synthez

import com.leon.synthez.core.SZImage
import com.leon.synthez.core.SZPalette
import com.leon.synthez.core.SZPoint
import com.leon.synthez.core.SZRGBA
import com.leon.synthez.core.SZRect
import com.leon.synthez.core.SZSize
import com.leon.synthez.core.SZSurface
import com.leon.synthez.core.SZUI
import com.leon.synthez.widgets.SZTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageTest {
    private val palette = SZTheme.synthPalette()

    @Test
    fun exactColorsMapToThemselves() {
        val argb = IntArray(4) { 0xFF000000.toInt() or palette.colors[it * 4].argb }
        val image = SZImage.quantize(4, 1, argb, palette, dither = 0f)
        assertEquals(listOf(0, 4, 8, 12), (0 until 4).map { image[it, 0] })
    }

    @Test
    fun gradientsDitherBetweenNeighbours() {
        // A mid grey halfway between two ramp steps becomes a mix of both, not one flat color.
        val a = palette.colors[6]
        val b = palette.colors[7]
        val mid = SZRGBA((a.r + b.r) / 2, (a.g + b.g) / 2, (a.b + b.b) / 2)
        val image = SZImage.quantize(4, 4, IntArray(16) { mid.argb }, palette)
        val used = image.pixels.map { it.toInt() and 0xFF }.toSet()
        assertEquals(setOf(6, 7), used)
    }

    @Test
    fun transparencyAndMatte() {
        val argb = intArrayOf(0x00FFFFFF, 0xFF000000.toInt())
        val keyed = SZImage.quantize(2, 1, argb, palette)
        assertEquals(SZImage.TRANSPARENT, keyed[0, 0])
        // Over a paper-colored matte, clear pixels take the paper color.
        val matted = SZImage.quantize(2, 1, argb, palette, matte = palette.colors[13].argb and 0xFFFFFF, dither = 0f)
        assertEquals(13, matted[0, 0])
        assertEquals(0, matted[1, 0])
    }

    @Test
    fun allowedColorsLimitTheResult() {
        val orange = 0xFFE3621E.toInt()
        val greysOnly = (0..13).toList().toIntArray()
        val image = SZImage.quantize(1, 1, intArrayOf(orange), SZPalette(palette.colors), colors = greysOnly, dither = 0f)
        assertTrue(image[0, 0] in 0..13)
    }

    @Test
    fun drawsScaledClippedAndSkipsTransparent() {
        val image = SZImage(2, 1, byteArrayOf(5, SZImage.TRANSPARENT.toByte()))
        val ui = SZUI(palette)
        ui.frame(0.0, SZSize(8, 4), emptyList()) {
            clip(SZRect(0, 0, 3, 4)) { image(image, SZPoint(0, 0), scale = 2) }
        }
        val surface = SZSurface(8, 4)
        surface.render(ui.drawList)
        val background = ui.drawList.background
        assertEquals(5, surface[0, 0])
        assertEquals(5, surface[1, 1])
        // The transparent half keeps the background; the clip stops at x = 3.
        assertEquals(background, surface[2, 0])
        assertEquals(background, surface[3, 0])
    }
}
