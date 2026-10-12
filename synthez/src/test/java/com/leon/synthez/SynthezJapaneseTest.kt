package com.leon.synthez

import com.leon.synthez.core.SZBDFGlyphs
import com.leon.synthez.core.SZBitmap
import com.leon.synthez.core.SZBitmapFont
import com.leon.synthez.core.SZDrawList
import com.leon.synthez.core.SZGlyphSource
import com.leon.synthez.core.SZPoint
import com.leon.synthez.core.SZRect
import com.leon.synthez.core.SZSize
import com.leon.synthez.core.SZUI
import com.leon.synthez.core.SZSurface
import com.leon.synthez.core.layout
import com.leon.synthez.widgets.SZTheme
import com.leon.synthez.widgets.SZThemeKey
import com.leon.synthez.widgets.button
import com.leon.synthez.widgets.pixel5x7
import com.leon.synthez.widgets.tabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private val K8X12 by lazy { SZBDFGlyphs(File("src/main/assets/synthez/k8x12.bdf").readBytes()) }

/** Renders rows of a bitmap as `#` and `.`, `width` columns wide. */
private fun art(bitmap: SZBitmap, width: Int = bitmap.width): List<String> =
    bitmap.rows.map { row -> (0 until width).joinToString("") { if (row and (1L shl it) != 0L) "#" else "." } }

class JapaneseTest {
    @Test
    fun bdfIndexesEveryGlyph() {
        assertEquals(7170, K8X12.count)
        assertEquals(10, K8X12.ascent)
        assertEquals(2, K8X12.descent)
        assertNull(K8X12.glyph(0x1F600))
    }

    @Test
    fun bdfDecodesIntoTheCell() {
        val a = K8X12.glyph('あ'.code)!!
        // Advance 8 minus the built-in spacing column.
        assertEquals(7, a.width)
        assertEquals(
            listOf(
                ".......",
                "..#....",
                ".#####.",
                "..#....",
                "..#.#..",
                "..####.",
                ".##.#.#",
                "#.#.#.#",
                "#.##..#",
                ".##..#.",
                ".......",
                ".......",
            ),
            art(a),
        )
        // A glyph that dips below the baseline lands in the descent rows.
        val period = K8X12.glyph('。'.code)!!
        assertEquals(".#.", art(period, 3)[10])
    }

    @Test
    fun fontLoadsGlyphsOnceOnDemand() {
        var asked = 0
        val counting = object : SZGlyphSource {
            override val ascent = K8X12.ascent
            override val descent = K8X12.descent
            override fun glyph(codePoint: Int): SZBitmap? {
                asked++
                return K8X12.glyph(codePoint)
            }
        }
        val font = SZBitmapFont.from("k8x12", counting)
        val before = font.glyphs.size
        val first = font.glyphIndex('曜'.code)
        assertEquals(first, font.glyphIndex('曜'.code))
        assertEquals(before + 1, font.glyphs.size)
        // A character the source lacks is asked once and drawn as the fallback.
        val missing = font.glyphIndex(0x1F600)
        assertEquals(missing, font.glyphIndex(0x1F600))
        assertEquals(font.glyphIndex('?'.code), missing)
        assertEquals(3, asked)
    }

    @Test
    fun compositeFontSharesABaseline() {
        val font = SZBitmapFont.pixel5x7(K8X12)
        assertEquals(12, font.glyphHeight)
        assertEquals(14, font.lineHeight)
        // 5×7 caps (lowercase maps to them) next to a 7-wide kana.
        assertEquals(5 + 1 + 7, font.measure("aあ"))
        val list = SZDrawList()
        list.begin(SZRect(0, 0, 13, 12), 0)
        list.appendText("Aあ", font, SZPoint.ZERO, 1, 1, 0, 0)
        val surface = SZSurface(13, 12)
        surface.render(list)
        fun lowestInk(x0: Int, x1: Int) = (0 until 12).last { y -> (x0 until x1).any { surface[it, y] == 1 } }
        // Both sit on the baseline under row 9.
        assertEquals(9, lowestInk(0, 5))
        assertEquals(9, lowestInk(6, 13))
    }

    @Test
    fun kinsokuKeepsPunctuationOffLineStarts() {
        val font = SZBitmapFont.from("k8x12", K8X12)
        // Five kana fill 39 pixels exactly, so the break would land before "。".
        val lines = font.layout("あいうえお。かきくけこ", 39).lines
        assertEquals("あいうえ", lines[0])
        assertTrue(lines[1].startsWith("お。"))
        // An opening bracket never ends a line.
        val bracket = font.layout("あいうえ「かきくけこ」", 39).lines
        assertTrue(bracket.none { it.endsWith("「") })
        assertTrue(bracket.none { it.startsWith("」") })
    }
}

class JapaneseMetricsTest {
    @Test
    fun compositeFontHasLowercaseWithDescenders() {
        val font = SZBitmapFont.pixel5x7(K8X12)
        // Lowercase no longer folds into caps.
        assertTrue(font.glyphIndex('k'.code) != font.glyphIndex('K'.code))
        val g = font.glyphs[font.glyphIndex('g'.code)]
        // The tail of g reaches the two rows below the baseline (rows 10 and 11).
        assertTrue(g.rows[10] != 0L && g.rows[11] != 0L)
        val x = font.glyphs[font.glyphIndex('x'.code)]
        assertEquals(0L, x.rows[10])
        // The caps-only option still folds.
        val caps = SZBitmapFont.pixel5x7(K8X12, lowercase = false)
        assertEquals(caps.glyphIndex('K'.code), caps.glyphIndex('k'.code))
    }

    @Test
    fun widgetsSizeThemselvesToTheFont() {
        val plain = SZTheme.synth()
        assertEquals(11, plain.controlHeight)
        assertEquals(13, plain.barHeight)
        val japanese = SZTheme.synth(SZBitmapFont.pixel5x7(K8X12))
        assertEquals(16, japanese.controlHeight)
        assertEquals(18, japanese.barHeight)

        val ui = SZUI(SZTheme.synthPalette())
        ui.setEnvironment(SZThemeKey, japanese)
        var buttonFrame = SZRect.ZERO
        var tabsFrame = SZRect.ZERO
        ui.frame(0.0, SZSize(200, 200), emptyList()) {
            gap = 0
            val before = cursor.y
            button("再生")
            buttonFrame = SZRect(0, before, 0, cursor.y - before)
            val tabsTop = cursor.y
            tabs(listOf("ライブ", "タイムフリー"), 0)
            tabsFrame = SZRect(0, tabsTop, 0, cursor.y - tabsTop)
        }
        assertEquals(16, buttonFrame.height)
        assertEquals(18, tabsFrame.height)
    }
}
