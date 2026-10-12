package com.leon.synthez.view

import android.content.Context
import com.leon.synthez.core.SZBDFGlyphs

/** Fonts bundled with the library. */
object SZFontAssets {
    @Volatile
    private var k8x12: SZBDFGlyphs? = null

    /**
     * k8x12 by Num Kadoma: 8×12 Japanese, all JIS level 1 and 2 kanji. Free
     * to use, modify and redistribute, commercially or not; its terms ship
     * as `assets/synthez/k8x12-LICENSE.txt`.
     *
     * Loads once (about 0.8 MB, a few milliseconds to index) and is shared.
     */
    fun k8x12(context: Context): SZBDFGlyphs = k8x12 ?: synchronized(this) {
        k8x12 ?: SZBDFGlyphs(context.applicationContext.assets.open("synthez/k8x12.bdf").use { it.readBytes() }).also { k8x12 = it }
    }
}
