package com.leon.synthez.core

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A CPU framebuffer of palette indices, one byte per virtual pixel.
 *
 * Rasterizing a whole 640×400 frame touches 256 KB, which keeps the CPU path
 * cheaper than round trips to the GPU and lets [SZDrawOp.Remap] and
 * [SZDrawOp.Mosaic] read back what was drawn before them.
 *
 * Performance-sensitive: every primitive clips first and then writes the
 * byte array directly.
 */
class SZSurface(width: Int, height: Int) {
    var width: Int = max(width, 0)
        private set
    var height: Int = max(height, 0)
        private set
    var pixels: ByteArray = ByteArray(this.width * this.height)
        private set

    val bounds: SZRect get() = SZRect(0, 0, width, height)

    fun resize(width: Int, height: Int) {
        val w = max(width, 0)
        val h = max(height, 0)
        if (w == this.width && h == this.height) return
        this.width = w
        this.height = h
        pixels = ByteArray(w * h)
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    // Replay

    /** Clears to the list's background and replays every layer. */
    fun render(list: SZDrawList) {
        clear(list.background)
        val screen = bounds
        val clips = list.clips.map { it.intersection(screen) }
        for (layer in list.layers) {
            for (command in layer) {
                draw(command.op, clips[command.clip], list)
            }
        }
    }

    fun clear(color: SZColor) {
        Arrays.fill(pixels, color.toByte())
    }

    private fun draw(op: SZDrawOp, clip: SZRect, list: SZDrawList) {
        if (clip.isEmpty) return
        when (op) {
            is SZDrawOp.Fill -> fill(op.rect, op.color, op.pattern, clip)
            is SZDrawOp.Stroke -> {
                val r = op.rect
                if (r.width <= 0 || r.height <= 0) return
                fill(SZRect(r.x, r.y, r.width, 1), op.color, SZPatterns.SOLID, clip)
                fill(SZRect(r.x, r.maxY - 1, r.width, 1), op.color, SZPatterns.SOLID, clip)
                fill(SZRect(r.x, r.y, 1, r.height), op.color, SZPatterns.SOLID, clip)
                fill(SZRect(r.maxX - 1, r.y, 1, r.height), op.color, SZPatterns.SOLID, clip)
            }
            is SZDrawOp.Line -> line(op.a, op.b, op.color, clip)
            is SZDrawOp.Circle -> circle(op.center, op.radius, op.color, op.filled, clip)
            is SZDrawOp.Pixel -> if (clip.contains(op.point)) pixels[op.point.y * width + op.point.x] = op.color.toByte()
            is SZDrawOp.Bitmap -> blit(op.bitmap, op.origin, op.color, op.scale, clip)
            is SZDrawOp.Image -> image(op.image, op.origin, op.scale, clip)
            is SZDrawOp.Text -> {
                val font = list.fonts[op.font]
                val glyphs = list.glyphs
                var x = op.origin.x
                val step = font.spacing * op.scale
                for (i in op.start until op.end) {
                    val glyph = font.glyphs[glyphs[i]]
                    if (x >= clip.maxX) break
                    if (x + glyph.width * op.scale > clip.minX) {
                        blit(glyph, SZPoint(x, op.origin.y), op.color, op.scale, clip)
                    }
                    x += glyph.width * op.scale + step
                }
            }
            is SZDrawOp.Remap -> remap(op.rect, op.map, op.pattern, clip)
            is SZDrawOp.Mosaic -> mosaic(op.rect, op.size, clip)
            is SZDrawOp.Filter -> filter(op.rect, op.field, op.maps, op.blend, clip)
        }
    }

    // Primitives

    fun fill(rect: SZRect, color: SZColor, pattern: SZPattern, clip: SZRect) {
        val r = rect.intersection(clip)
        if (r.isEmpty || pattern == 0) return
        val c = color.toByte()
        val px = pixels
        val w = width
        if (pattern == SZPatterns.SOLID) {
            for (y in r.minY until r.maxY) {
                val row = y * w
                Arrays.fill(px, row + r.minX, row + r.maxX, c)
            }
        } else {
            for (y in r.minY until r.maxY) {
                val row = y * w
                val bits = pattern ushr ((y and 3) shl 2)
                for (x in r.minX until r.maxX) {
                    if (bits and (1 shl (x and 3)) != 0) px[row + x] = c
                }
            }
        }
    }

    fun blit(bitmap: SZBitmap, origin: SZPoint, color: SZColor, scale: Int, clip: SZRect) {
        val s = max(scale, 1)
        val r = SZRect(origin.x, origin.y, bitmap.width * s, bitmap.height * s).intersection(clip)
        if (r.isEmpty) return
        val c = color.toByte()
        val px = pixels
        val w = width
        val rows = bitmap.rows
        for (y in r.minY until r.maxY) {
            val bits = rows[(y - origin.y) / s]
            if (bits == 0L) continue
            val row = y * w
            if (s == 1) {
                for (x in r.minX until r.maxX) {
                    if (bits and (1L shl (x - origin.x)) != 0L) px[row + x] = c
                }
            } else {
                for (x in r.minX until r.maxX) {
                    if (bits and (1L shl ((x - origin.x) / s)) != 0L) px[row + x] = c
                }
            }
        }
    }

    fun image(image: SZImage, origin: SZPoint, scale: Int, clip: SZRect) {
        val s = max(scale, 1)
        val r = SZRect(origin.x, origin.y, image.width * s, image.height * s).intersection(clip)
        if (r.isEmpty) return
        val px = pixels
        val src = image.pixels
        val w = width
        val transparent = SZImage.TRANSPARENT.toByte()
        for (y in r.minY until r.maxY) {
            val row = y * w
            val srcRow = (y - origin.y) / s * image.width
            for (x in r.minX until r.maxX) {
                val c = src[srcRow + (x - origin.x) / s]
                if (c != transparent) px[row + x] = c
            }
        }
    }

    fun line(a: SZPoint, b: SZPoint, color: SZColor, clip: SZRect) {
        if (a.y == b.y) {
            fill(SZRect(min(a.x, b.x), a.y, abs(b.x - a.x) + 1, 1), color, SZPatterns.SOLID, clip)
            return
        }
        if (a.x == b.x) {
            fill(SZRect(a.x, min(a.y, b.y), 1, abs(b.y - a.y) + 1), color, SZPatterns.SOLID, clip)
            return
        }
        val c = color.toByte()
        var x = a.x
        var y = a.y
        val dx = abs(b.x - a.x)
        val dy = -abs(b.y - a.y)
        val sx = if (a.x < b.x) 1 else -1
        val sy = if (a.y < b.y) 1 else -1
        var err = dx + dy
        while (true) {
            if (clip.contains(x, y)) pixels[y * width + x] = c
            if (x == b.x && y == b.y) break
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                x += sx
            }
            if (e2 <= dx) {
                err += dx
                y += sy
            }
        }
    }

    fun circle(center: SZPoint, radius: Int, color: SZColor, filled: Boolean, clip: SZRect) {
        if (radius < 0) return
        val c = color.toByte()
        val cx = center.x
        val cy = center.y
        fun span(x0: Int, x1: Int, y: Int) = fill(SZRect(x0, y, x1 - x0 + 1, 1), color, SZPatterns.SOLID, clip)
        fun plot(px: Int, py: Int) {
            if (clip.contains(px, py)) pixels[py * width + px] = c
        }
        var x = radius
        var y = 0
        var err = 1 - radius
        while (x >= y) {
            if (filled) {
                span(cx - x, cx + x, cy + y)
                span(cx - x, cx + x, cy - y)
                span(cx - y, cx + y, cy + x)
                span(cx - y, cx + y, cy - x)
            } else {
                plot(cx + x, cy + y); plot(cx - x, cy + y)
                plot(cx + x, cy - y); plot(cx - x, cy - y)
                plot(cx + y, cy + x); plot(cx - y, cy + x)
                plot(cx + y, cy - x); plot(cx - y, cy - x)
            }
            y += 1
            if (err < 0) {
                err += 2 * y + 1
            } else {
                x -= 1
                err += 2 * (y - x) + 1
            }
        }
    }

    fun remap(rect: SZRect, map: SZColorMap, pattern: SZPattern, clip: SZRect) {
        val r = rect.intersection(clip)
        if (r.isEmpty || pattern == 0) return
        val px = pixels
        val w = width
        val table = map.table
        for (y in r.minY until r.maxY) {
            val row = y * w
            val bits = pattern ushr ((y and 3) shl 2)
            for (x in r.minX until r.maxX) {
                if (bits and (1 shl (x and 3)) != 0) px[row + x] = table[px[row + x].toInt() and 0xFF]
            }
        }
    }

    fun filter(rect: SZRect, field: SZStrengthField, maps: SZFilterMaps, blend: SZFilterBlend, clip: SZRect) {
        val r = rect.intersection(clip)
        if (r.isEmpty || maps.maps.isEmpty()) return
        val full = maps.maps.last()
        val levels = maps.maps.size - 1
        if (field is SZStrengthField.Constant) {
            // One map for the whole rectangle.
            val s = field.strength.coerceIn(0.0, 1.0)
            when (blend) {
                SZFilterBlend.STEPS -> remap(r, maps.maps[(s * levels).roundToIntAway()], SZPatterns.SOLID, clip)
                SZFilterBlend.DITHER -> remap(r, full, SZPatterns.shade(s), clip)
            }
            return
        }
        val px = pixels
        val w = width
        for (y in r.minY until r.maxY) {
            val row = y * w
            for (x in r.minX until r.maxX) {
                val s = field.strength(x, y).coerceIn(0.0, 1.0)
                when (blend) {
                    SZFilterBlend.DITHER -> {
                        val threshold = SZPatterns.BAYER[((y and 3) shl 2) or (x and 3)]
                        if (threshold < (s * 16).roundToIntAway()) {
                            px[row + x] = full.table[px[row + x].toInt() and 0xFF]
                        }
                    }
                    SZFilterBlend.STEPS -> {
                        val level = (s * levels).roundToIntAway()
                        if (level > 0) px[row + x] = maps.maps[level].table[px[row + x].toInt() and 0xFF]
                    }
                }
            }
        }
    }

    fun mosaic(rect: SZRect, size: Int, clip: SZRect) {
        if (size <= 1) return
        val r = rect.intersection(clip)
        if (r.isEmpty) return
        val px = pixels
        val w = width
        var by = r.minY
        while (by < r.maxY) {
            val bh = min(size, r.maxY - by)
            var bx = r.minX
            while (bx < r.maxX) {
                val bw = min(size, r.maxX - bx)
                val c = px[(by + bh / 2) * w + bx + bw / 2]
                for (y in by until by + bh) {
                    Arrays.fill(px, y * w + bx, y * w + bx + bw, c)
                }
                bx += size
            }
            by += size
        }
    }
}
