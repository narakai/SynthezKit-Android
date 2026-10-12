package com.leon.synthez.core

import kotlin.math.max

/**
 * Where a font finds glyphs it was not built with, such as the kanji of a
 * BDF file. Glyphs come back in the source's own cell: row 0 is [ascent]
 * rows above the baseline, and each bitmap's width is its advance.
 */
interface SZGlyphSource {
    /** Rows above the baseline. */
    val ascent: Int

    /** Rows below the baseline. */
    val descent: Int

    /** The glyph for `codePoint`, or null if the source lacks it. */
    fun glyph(codePoint: Int): SZBitmap?
}

/**
 * A 1-bit font whose glyphs are [SZBitmap]s.
 *
 * Each glyph's bitmap width is its advance, so the same type covers
 * monospaced and proportional fonts. ASCII resolves through a flat table;
 * everything else through a map. Text is read as Unicode code points.
 *
 * Characters the font was not built with are asked of `source` the first
 * time they are drawn or measured, then kept: a Japanese font loads only the
 * kanji a screen uses. Glyph indices never change once handed out.
 *
 * Performance-sensitive: [glyphIndex] and [measure] run for every character
 * drawn every frame. Fonts are compared by identity. Not thread-safe.
 *
 * @param glyphs The characters (first code point of each string) and the bitmaps that draw them.
 * @param fallback Drawn for characters neither the font nor its source has; it must be one of `glyphs`' keys.
 * @param caps Maps lowercase ASCII to uppercase before lookup, for caps-only fonts.
 * @param source Supplies the glyphs `glyphs` lacks.
 * @param sourceTop Blank rows above each source glyph, to line its baseline up with the font's.
 */
class SZBitmapFont(
    val name: String,
    glyphs: List<Pair<String, SZBitmap>>,
    /** Pixels between glyphs. */
    val spacing: Int = 1,
    lineHeight: Int? = null,
    val caps: Boolean = false,
    fallback: String = "?",
    private val source: SZGlyphSource? = null,
    private val sourceTop: Int = 0,
) {
    private val store = ArrayList<SZBitmap>(glyphs.size)

    /** Every glyph loaded so far, by index. */
    val glyphs: List<SZBitmap> get() = store

    /** The height of one line, including the gap below it. */
    val lineHeight: Int

    /** The tallest glyph, counting glyphs the source may still supply. */
    val glyphHeight: Int

    private val ascii = IntArray(128) { -1 }
    private val others = HashMap<Int, Int>()
    private val fallback: Int

    init {
        require(glyphs.isNotEmpty()) { "A font needs at least one glyph" }
        val fallbackCodePoint = fallback.codePointAt(0)
        var fallbackIndex = -1
        glyphs.forEachIndexed { index, (character, bitmap) ->
            store.add(bitmap)
            if (character.isNotEmpty()) {
                val cp = character.codePointAt(0)
                if (cp < 128) ascii[cp] = index else others[cp] = index
                if (cp == fallbackCodePoint) fallbackIndex = index
            }
        }
        this.fallback = max(fallbackIndex, 0)
        val sourceHeight = source?.let { sourceTop + it.ascent + it.descent } ?: 0
        glyphHeight = max(store.maxOf { it.height }, sourceHeight)
        this.lineHeight = lineHeight ?: (glyphHeight + 1)
    }

    /** Adds `alias` as another name for the glyph of `character`. */
    fun alias(alias: String, character: String) {
        if (alias.isEmpty() || character.isEmpty()) return
        val index = glyphIndex(character.codePointAt(0))
        val target = alias.codePointAt(0)
        if (target < 128) ascii[target] = index else others[target] = index
    }

    fun glyphIndex(codePoint: Int): Int {
        var cp = codePoint
        if (caps && cp in 0x61..0x7A) cp -= 0x20
        if (cp in 0 until 128) {
            val index = ascii[cp]
            if (index >= 0) return index
            if (source == null) return fallback
        }
        others[cp]?.let { return it }
        return load(cp)
    }

    /** Asks the source once; a miss is remembered as the fallback. */
    private fun load(cp: Int): Int {
        val glyph = source?.glyph(cp)
        val index = if (glyph == null) {
            fallback
        } else {
            store.add(if (sourceTop > 0) SZBitmap(glyph.width, LongArray(sourceTop + glyph.height) { if (it < sourceTop) 0L else glyph.rows[it - sourceTop] }) else glyph)
            store.size - 1
        }
        if (cp in 0 until 128) ascii[cp] = index else others[cp] = index
        return index
    }

    /** The advance of one code point, without spacing. */
    fun advance(codePoint: Int): Int = store[glyphIndex(codePoint)].width

    /** The width of `text` on one line, without trailing spacing. */
    fun measure(text: CharSequence): Int {
        var width = 0
        var count = 0
        text.forEachCodePoint { cp ->
            width += store[glyphIndex(cp)].width
            count++
        }
        return if (count == 0) 0 else width + (count - 1) * spacing
    }

    companion object {
        /** A font drawn entirely from `source`, such as a BDF file. */
        fun from(name: String, source: SZGlyphSource, spacing: Int = 1, lineHeight: Int? = null, caps: Boolean = false): SZBitmapFont {
            val fallback = source.glyph('?'.code) ?: SZBitmap(0, LongArray(source.ascent + source.descent))
            return SZBitmapFont(name, listOf("?" to fallback), spacing, lineHeight, caps, "?", source)
        }
    }
}

internal inline fun CharSequence.forEachCodePoint(action: (Int) -> Unit) {
    var i = 0
    while (i < length) {
        val cp = Character.codePointAt(this, i)
        action(cp)
        i += Character.charCount(cp)
    }
}

/** Text broken into lines for a width. */
data class SZTextLayout(
    val lines: List<String>,
    /** The widest line. */
    val width: Int,
    val lineHeight: Int,
) {
    val height: Int get() = lines.size * lineHeight
}

enum class SZTextWrap {
    /** One line per hard break. */
    NONE,

    /** Breaks at spaces, and inside words longer than a line. */
    WORD,

    /** Breaks anywhere. */
    CHARACTER,
}

/** Greedy line breaking: as many words per line as fit `width`. */
fun SZBitmapFont.layout(text: String, width: Int, scale: Int = 1, wrap: SZTextWrap = SZTextWrap.WORD): SZTextLayout {
    val s = max(scale, 1)
    val limit = max(width / s, 1)
    val lines = ArrayList<String>()
    var widest = 0
    for (paragraph in text.split('\n')) {
        if (wrap == SZTextWrap.NONE || measure(paragraph) <= limit) {
            lines.add(paragraph)
            widest = max(widest, measure(paragraph))
            continue
        }
        val n = paragraph.length
        var start = 0
        while (start < n) {
            var end = start
            var lastSpace = -1
            var lineWidth = 0
            var index = start
            while (index < n) {
                val cp = paragraph.codePointAt(index)
                val next = lineWidth + (if (index == start) 0 else spacing) + advance(cp)
                if (next > limit && index > start) break
                lineWidth = next
                if (cp == ' '.code) lastSpace = index
                index += Character.charCount(cp)
                end = index
            }
            if (end < n && wrap == SZTextWrap.WORD && lastSpace > start) {
                end = lastSpace
            } else if (end < n) {
                end = kinsoku(paragraph, start, end)
            }
            val line = paragraph.substring(start, end)
            val trimmed = if (line.endsWith(" ")) line.dropLast(1) else line
            lines.add(trimmed)
            widest = max(widest, measure(trimmed))
            start = end
            while (start < n && paragraph[start] == ' ') start++
        }
    }
    return SZTextLayout(lines, widest * s, lineHeight * s)
}

/** Closing punctuation and small kana, which must not begin a line. */
private val NO_LINE_START: Set<Int> = buildSet {
    "、。，．・：；？！ー～…‥ゝゞヽヾ々〻）］｝」』】〕〉》〙〗〟’”»ぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶ,.!?:;)]}".forEachCodePoint { add(it) }
}

/** Opening punctuation, which must not end a line. */
private val NO_LINE_END: Set<Int> = buildSet {
    "（［｛「『【〔〈《〘〖〝‘“«([{".forEachCodePoint { add(it) }
}

/**
 * Japanese line breaking (kinsoku): moves a break that falls inside a run
 * of text so the next line does not start with closing punctuation and this
 * one does not end with opening punctuation. Keeps `end` if nothing would
 * be left on the line.
 */
private fun kinsoku(text: String, start: Int, end: Int): Int {
    var e = end
    while (e > start && e < text.length && text.codePointAt(e) in NO_LINE_START) e = text.offsetByCodePoints(e, -1)
    while (e > start && text.codePointBefore(e) in NO_LINE_END) e = text.offsetByCodePoints(e, -1)
    return if (e > start) e else end
}

/**
 * Keeps wrapped text between frames: a layout is reused while the same text,
 * font, width and scale are drawn, and dropped a few frames after it stops
 * being asked for.
 */
class SZTextLayoutCache {
    private data class Key(val text: String, val font: SZBitmapFont, val width: Int, val scale: Int, val wrap: SZTextWrap)

    private class Entry(val layout: SZTextLayout, var frame: Long)

    private val entries = HashMap<Key, Entry>()
    private var frame = 0L

    /** Frames an unused layout survives. */
    var lifetime = 30L
    var hits = 0
        private set
    var misses = 0
        private set

    val count: Int get() = entries.size

    fun layout(text: String, font: SZBitmapFont, width: Int, scale: Int, wrap: SZTextWrap): SZTextLayout {
        val key = Key(text, font, width, scale, wrap)
        entries[key]?.let {
            it.frame = frame
            hits++
            return it.layout
        }
        misses++
        val layout = font.layout(text, width, scale, wrap)
        entries[key] = Entry(layout, frame)
        return layout
    }

    internal fun endFrame(number: Long) {
        frame = number
        if (number % 30 != 0L || entries.isEmpty()) return
        val keep = lifetime
        entries.values.removeAll { number - it.frame > keep }
    }
}
