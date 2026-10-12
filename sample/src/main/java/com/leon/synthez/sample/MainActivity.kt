package com.leon.synthez.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.leon.synthez.compose.SZCanvas
import com.leon.synthez.core.SZResolution
import com.leon.synthez.sample.gallery.SZGallery
import com.leon.synthez.view.SZCanvasView
import com.leon.synthez.view.SZFontAssets
import com.leon.synthez.widgets.SZTheme

/**
 * The gallery in a Compose canvas. The canvas body reads and writes the
 * gallery, a plain object, every frame, and hands its CRT settings to the view.
 *
 * Launch extras, for screenshots: `--ef scale 2` (virtual pixel size in dp),
 * `--es page CRT`, `--ez crt true`, `--ef pxscale 3` (whole device pixels
 * per virtual pixel, instead of `scale`).
 */
class MainActivity : ComponentActivity() {
    private val gallery by lazy { SZGallery(gridCount = 24, cjk = SZFontAssets.k8x12(this)) }
    private val palette = SZTheme.synthPalette()
    private var canvas: SZCanvasView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scale = intent.getFloatExtra("scale", 1f).toDouble()
        intent.getStringExtra("page")?.let { name -> SZGallery.Page.entries.firstOrNull { it.name == name }?.let { gallery.page = it } }
        if (intent.getBooleanExtra("crt", false)) gallery.crt = gallery.crt.copy(isEnabled = true)
        val pixelScale = intent.getFloatExtra("pxscale", 0f).toDouble()

        setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF14110F)).safeDrawingPadding()) {
                SZCanvas(
                    palette = palette,
                    modifier = Modifier.fillMaxSize(),
                    background = 1,
                    resolution = if (pixelScale > 0) SZResolution.PixelScale(pixelScale) else SZResolution.PointScale(scale),
                    configure = { canvas = it },
                ) {
                    gallery.draw(this)
                    canvas?.crt = gallery.crt
                }
            }
        }
    }
}
