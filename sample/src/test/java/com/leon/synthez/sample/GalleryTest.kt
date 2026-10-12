package com.leon.synthez.sample

import com.leon.synthez.core.SZBDFGlyphs
import com.leon.synthez.core.SZFrameResult
import com.leon.synthez.core.SZSize
import com.leon.synthez.core.SZUI
import com.leon.synthez.sample.gallery.SZGallery
import com.leon.synthez.widgets.SZTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private val K8X12 by lazy { SZBDFGlyphs(File("../synthez/src/main/assets/synthez/k8x12.bdf").readBytes()) }

class GalleryTest {
    @Test
    fun galleryIsStableWhenIdle() {
        val ui = SZUI(SZTheme.synthPalette())
        val gallery = SZGallery()
        var last: SZFrameResult? = null
        for (i in 0 until 60) {
            last = ui.frame(i / 60.0, SZSize(640, 400), emptyList()) { gallery.draw(this) }
        }
        // Cards measure their content on the first frame, then settle.
        assertFalse(last!!.changed)
        assertFalse(last.needsAnotherFrame)
        assertTrue(ui.drawList.commandCount > 100)
    }

    @Test
    fun everyPageBuildsAndSettles() {
        for (page in SZGallery.Page.entries) {
            val ui = SZUI(SZTheme.synthPalette())
            val gallery = SZGallery(cjk = K8X12)
            gallery.page = page
            var last = SZFrameResult(needsAnotherFrame = true, changed = true)
            for (size in listOf(SZSize(402, 790), SZSize(640, 400))) {
                for (i in 0 until 90) {
                    last = ui.frame(i / 60.0, size, emptyList()) { gallery.draw(this) }
                }
                assertTrue("$page draws", ui.drawList.commandCount > 50)
                assertTrue("$page describes itself", ui.accessibilityItems.size > 5)
            }
            // The CRT page sweeps a bar forever; the others come to rest.
            assertEquals("$page settles", page == SZGallery.Page.CRT, last.needsAnotherFrame)
        }
    }
}
