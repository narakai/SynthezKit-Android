package com.leon.synthez.core

import java.util.IdentityHashMap

/** One drawing primitive. Coordinates are absolute virtual pixels. */
sealed class SZDrawOp {
    data class Fill(val rect: SZRect, val color: SZColor, val pattern: SZPattern) : SZDrawOp()

    /** A 1-pixel outline inside the rectangle. */
    data class Stroke(val rect: SZRect, val color: SZColor) : SZDrawOp()

    /** Bresenham, both ends included. */
    data class Line(val a: SZPoint, val b: SZPoint, val color: SZColor) : SZDrawOp()

    data class Circle(val center: SZPoint, val radius: Int, val color: SZColor, val filled: Boolean) : SZDrawOp()

    data class Pixel(val point: SZPoint, val color: SZColor) : SZDrawOp()

    data class Bitmap(val bitmap: SZBitmap, val origin: SZPoint, val color: SZColor, val scale: Int) : SZDrawOp()

    /** Palette-indexed pixels; [SZImage.TRANSPARENT] is skipped. */
    data class Image(val image: SZImage, val origin: SZPoint, val scale: Int) : SZDrawOp()

    /** Glyphs `start until end` of [SZDrawList.glyphs], in the font at slot `font` of [SZDrawList.fonts]. */
    data class Text(val font: Int, val start: Int, val end: Int, val origin: SZPoint, val color: SZColor, val scale: Int) : SZDrawOp()

    /** Maps pixels already drawn through `map` where `pattern` covers them. */
    data class Remap(val rect: SZRect, val map: SZColorMap, val pattern: SZPattern) : SZDrawOp()

    /** Pixelates: each `size`×`size` block takes the color at its center. */
    data class Mosaic(val rect: SZRect, val size: Int) : SZDrawOp()

    /** Moves what is drawn toward a color by a strength field. */
    data class Filter(val rect: SZRect, val field: SZStrengthField, val maps: SZFilterMaps, val blend: SZFilterBlend) : SZDrawOp()
}

data class SZDrawCommand(
    /** Index into [SZDrawList.clips]. */
    val clip: Int,
    val op: SZDrawOp,
)

/**
 * The commands of one frame, bucketed by layer.
 *
 * Overlays are drawn in the middle of their parents' content but must land
 * on top, so commands collect per layer and replay layer by layer. Every
 * command carries the index of its clip rectangle, so replay needs no clip
 * stack.
 *
 * A list keeps its storage between frames, and [isEqual] lets the host skip
 * rasterizing a frame that came out the same as the last.
 */
class SZDrawList {
    private val layerStore = ArrayList<ArrayList<SZDrawCommand>>()
    private val clipStore = ArrayList<SZRect>()
    private val fontStore = ArrayList<SZBitmapFont>()
    private val fontSlots = IdentityHashMap<SZBitmapFont, Int>()

    val layers: List<List<SZDrawCommand>> get() = layerStore
    val clips: List<SZRect> get() = clipStore

    /** Fonts by slot. Fonts stay registered across frames, so slots are stable. */
    val fonts: List<SZBitmapFont> get() = fontStore

    /** Glyph indices of every text command, back to back; the first [glyphCount] are in use. */
    var glyphs = IntArray(256)
        private set
    var glyphCount = 0
        private set

    var background: SZColor = 0
        private set

    val commandCount: Int get() = layerStore.sumOf { it.size }

    /** Clears the list for a new frame; clip 0 becomes `screen`. */
    fun begin(screen: SZRect, background: SZColor) {
        for (layer in layerStore) layer.clear()
        clipStore.clear()
        clipStore.add(screen)
        glyphCount = 0
        this.background = background
    }

    /** Registers a clip rectangle and returns its index. */
    fun addClip(rect: SZRect): Int {
        clipStore.add(rect)
        return clipStore.size - 1
    }

    fun append(op: SZDrawOp, clip: Int, layer: Int) {
        while (layer >= layerStore.size) layerStore.add(ArrayList())
        layerStore[layer].add(SZDrawCommand(clip, op))
    }

    /** Resolves `text` to glyphs, records it, and returns its width. */
    fun appendText(text: CharSequence, font: SZBitmapFont, origin: SZPoint, color: SZColor, scale: Int, clip: Int, layer: Int): Int {
        val start = glyphCount
        var width = 0
        text.forEachCodePoint { cp ->
            val index = font.glyphIndex(cp)
            if (glyphCount == glyphs.size) glyphs = glyphs.copyOf(glyphs.size * 2)
            glyphs[glyphCount++] = index
            width += font.glyphs[index].width + font.spacing
        }
        if (glyphCount <= start) return 0
        append(SZDrawOp.Text(slot(font), start, glyphCount, origin, color, scale), clip, layer)
        return (width - font.spacing) * scale
    }

    private fun slot(font: SZBitmapFont): Int = fontSlots.getOrPut(font) {
        fontStore.add(font)
        fontStore.size - 1
    }

    /**
     * Whether `other` draws exactly what this list draws.
     *
     * Empty trailing layers are ignored, so a frame that stopped using an
     * overlay layer still compares equal to one that never had it.
     */
    fun isEqual(other: SZDrawList): Boolean {
        if (background != other.background || clipStore != other.clipStore || glyphCount != other.glyphCount) return false
        for (i in 0 until glyphCount) {
            if (glyphs[i] != other.glyphs[i]) return false
        }
        val a = layerStore.indexOfLast { it.isNotEmpty() }
        val b = other.layerStore.indexOfLast { it.isNotEmpty() }
        if (a != b) return false
        for (i in 0..a) {
            if (layerStore[i] != other.layerStore[i]) return false
        }
        // Font slots must name the same fonts for text to match.
        val used = minOf(fontStore.size, other.fontStore.size)
        for (i in 0 until used) {
            if (fontStore[i] !== other.fontStore[i]) return false
        }
        return true
    }
}
