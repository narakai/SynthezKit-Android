package com.leon.synthez.widgets

import com.leon.synthez.core.SZBitmapFont
import com.leon.synthez.core.SZColor
import com.leon.synthez.core.SZContext
import com.leon.synthez.core.SZEnvironmentKey
import com.leon.synthez.core.SZInteraction
import com.leon.synthez.core.SZPalette
import com.leon.synthez.core.SZRGBA

/** Ink, fill and border for a control in one state. */
data class SZTone(val ink: SZColor, val fill: SZColor, val border: SZColor)

/** The palette indices and fonts widgets draw with. */
data class SZTheme(
    val background: SZColor,
    val panel: SZColor,
    val line: SZColor,
    val lineStrong: SZColor,
    val textDim: SZColor,
    val text: SZColor,
    val paper: SZColor,
    val accentDim: SZColor,
    val accent: SZColor,
    val accentBright: SZColor,
    val font: SZBitmapFont,
) {
    /** Buttons, chips and other one-line controls: the font plus two pixels above and below. 11 for 5×7. */
    val controlHeight: Int get() = font.glyphHeight + 4

    /** Bars of options: segmented controls, tabs, steppers, dropdowns, menu rows. 13 for 5×7. */
    val barHeight: Int get() = font.glyphHeight + 6

    /** The top of a line of text centered in a span `height` tall starting at `y`. */
    fun textTop(y: Int, height: Int): Int = y + (height - font.glyphHeight) / 2

    /** Ink, fill and border for a control at rest, hovered or held. */
    fun tone(it: SZInteraction, on: Boolean = false, disabled: Boolean = false): SZTone = when {
        disabled -> SZTone(textDim, background, line)
        on -> SZTone(background, if (it.held) accentBright else accent, accent)
        it.held -> SZTone(background, text, text)
        it.hovered -> SZTone(paper, panel, textDim)
        else -> SZTone(text, background, lineStrong)
    }

    companion object {
        /** Fourteen warm greys from black to paper, then four oranges, ordered by lightness so color maps always land on a real step. */
        fun synthPalette(): SZPalette = SZPalette(
            listOf(
                0x0B0908, 0x14110F, 0x1C1815, 0x25201C, 0x2F2924, 0x3A332D, 0x4A4139,
                0x5C5148, 0x706359, 0x86786C, 0x9E9083, 0xB7A99B, 0xD0C3B5, 0xE8DDCF,
                0x5A2410, 0x9C3D14, 0xE3621E, 0xFF9A4D,
            ).map { SZRGBA.hex(it) },
        )

        /** The theme for [synthPalette]. */
        fun synth(font: SZBitmapFont = SZBitmapFont.pixel5x7()) = SZTheme(
            background = 1,
            panel = 2,
            line = 4,
            lineStrong = 6,
            textDim = 8,
            text = 11,
            paper = 13,
            accentDim = 15,
            accent = 16,
            accentBright = 17,
            font = font,
        )
    }
}

/** The theme widgets read from [com.leon.synthez.core.SZUI.environment]. */
object SZThemeKey : SZEnvironmentKey<SZTheme> {
    override val defaultValue: SZTheme by lazy { SZTheme.synth() }
}

val SZContext.theme: SZTheme get() = ui.environment(SZThemeKey)
