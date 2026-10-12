package com.leon.synthez.compose

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.leon.synthez.core.SZCRTSettings
import com.leon.synthez.core.SZColor
import com.leon.synthez.core.SZContext
import com.leon.synthez.core.SZPalette
import com.leon.synthez.core.SZResolution
import com.leon.synthez.view.SZCanvasView

/**
 * A composable that hosts an [SZCanvasView].
 *
 * ```kotlin
 * SZCanvas(palette = palette, background = 1) {
 *     if (button("+").clicked) model.count++
 *     label(model.count.toString())
 * }
 * ```
 *
 * The content runs once per frame, not when Compose recomposes. Snapshot
 * state it reads (`mutableStateOf`, `collectAsState`) is observed: a change
 * draws a new frame. State kept in plain objects needs
 * [SZCanvasView.invalidateContent], reachable through `configure`.
 *
 * @param crt The CRT settings, or null to leave them to the view.
 * @param configure Called once with the view after it is created, for anything this wrapper does not expose.
 */
@Composable
fun SZCanvas(
    palette: SZPalette,
    modifier: Modifier = Modifier,
    background: SZColor = 0,
    resolution: SZResolution = SZResolution.PixelScale(3.0),
    crt: SZCRTSettings? = null,
    configure: (SZCanvasView) -> Unit = {},
    content: SZContext.() -> Unit,
) {
    val latestContent by rememberUpdatedState(content)
    val main = remember { Handler(Looper.getMainLooper()) }
    val observer = remember {
        SnapshotStateObserver { change ->
            if (Looper.myLooper() == Looper.getMainLooper()) change() else main.post(change)
        }
    }
    val onChanged = remember { { view: SZCanvasView -> view.invalidateContent() } }
    val canvas = remember { CanvasRef() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SZCanvasView(context, palette, background, resolution).also { view ->
                view.body = {
                    val ctx = this
                    observer.observeReads(view, onChanged) { latestContent(ctx) }
                }
                configure(view)
            }
        },
        update = { view ->
            canvas.view = view
            if (view.ui.palette !== palette) {
                view.ui.palette = palette
                view.invalidateContent()
            }
            if (view.ui.background != background) {
                view.ui.background = background
                view.invalidateContent()
            }
            view.resolution = resolution
            crt?.let { view.crt = it }
        },
    )

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, observer) {
        observer.start()
        val events = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> canvas.view?.onPause()
                Lifecycle.Event.ON_RESUME -> canvas.view?.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(events)
        onDispose {
            lifecycle.removeObserver(events)
            observer.stop()
            observer.clear()
        }
    }
}

private class CanvasRef {
    var view: SZCanvasView? = null
}
