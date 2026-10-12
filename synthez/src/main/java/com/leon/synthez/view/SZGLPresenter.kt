package com.leon.synthez.view

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.leon.synthez.core.SZCRTSettings
import com.leon.synthez.core.SZColor
import com.leon.synthez.core.SZPalette
import com.leon.synthez.core.SZScreen
import com.leon.synthez.core.SZSurface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Puts an indexed [SZSurface] on screen with OpenGL ES 3.0.
 *
 * The surface is uploaded as an `R8UI` texture and looked up in the palette
 * on the GPU. Without CRT, it is scaled with "sharp bilinear" filtering,
 * which blends only across the one device pixel straddling a virtual pixel
 * edge, so fractional scales stay crisp.
 *
 * With CRT, every frame runs, in linear light:
 * 1. phosphor: the new frame or the previous light times its decay,
 *    whichever is brighter, per channel (afterglow);
 * 2. halation: a half-size chain blurred down and added back up;
 * 3. present: Gaussian beams over neighbouring scanlines whose width grows
 *    with brightness, a shadow mask, the glow, a vignette and glass
 *    reflection, then sRGB encoding.
 *
 * The main thread hands frames over with [submit]; the GL thread draws the
 * latest one. Frames that arrive faster than the GPU draws them coalesce,
 * keeping their afterglow time.
 */
internal class SZGLPresenter : GLSurfaceView.Renderer {
    private class Frame {
        var pixels = ByteArray(0)
        var width = 0
        var height = 0
        var upload = false
        val palette = IntArray(256)
        var paletteVersion = -1L
        var background = 0
        var screen: SZScreen? = null
        var crt = SZCRTSettings()
        var dt = 0.0
    }

    private val lock = Any()
    private var pending = Frame()
    private var front = Frame()
    private var hasPending = false

    /** Called on the main thread with a finished surface. */
    fun submit(surface: SZSurface, upload: Boolean, palette: SZPalette, background: SZColor, screen: SZScreen, crt: SZCRTSettings, dt: Double) {
        synchronized(lock) {
            val f = pending
            val n = surface.width * surface.height
            if (f.pixels.size != n) f.pixels = ByteArray(n)
            // Always copied, so the GL thread holds the latest pixels if its context is lost.
            System.arraycopy(surface.pixels, 0, f.pixels, 0, n)
            f.upload = upload || (hasPending && f.upload)
            f.width = surface.width
            f.height = surface.height
            if (f.paletteVersion != palette.version) {
                f.palette.fill(0xFF000000.toInt())
                palette.colors.forEachIndexed { i, c -> f.palette[i] = c.argb }
                f.paletteVersion = palette.version
            }
            f.background = background
            f.screen = screen
            f.crt = crt
            f.dt = if (hasPending) minOf(f.dt + dt, 0.1) else dt
            hasPending = true
        }
    }

    // GL state, touched only on the GL thread.

    private class Program(val id: Int) {
        private val locations = HashMap<String, Int>()
        fun uniform(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    }

    private class Target(val texture: Int, val framebuffer: Int, val width: Int, val height: Int)

    private var ready = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var vertexArray = 0
    private lateinit var plain: Program
    private lateinit var phosphor: Program
    private lateinit var down: Program
    private lateinit var up: Program
    private lateinit var present: Program
    private var paletteTexture = 0
    private var uploadedPalette = -1L
    private val paletteBuffer: ByteBuffer = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder())
    private var indexTexture = 0
    private var indexWidth = 0
    private var indexHeight = 0
    private var indexBuffer: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var lights = emptyList<Target>()
    private var lightSlot = 0
    private var lightNeedsClear = true
    private var bloom = emptyList<Target>()
    private var halfFloat = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: every object from the old one is gone.
        ready = false
        lights = emptyList()
        bloom = emptyList()
        indexTexture = 0
        indexWidth = 0
        indexHeight = 0
        paletteTexture = 0
        uploadedPalette = -1
        lightNeedsClear = true
        try {
            val ids = IntArray(1)
            GLES30.glGenVertexArrays(1, ids, 0)
            vertexArray = ids[0]
            plain = link(SZShaders.PLAIN, "uIndices" to 0, "uPalette" to 1)
            phosphor = link(SZShaders.PHOSPHOR, "uIndices" to 0, "uPalette" to 1, "uPrevious" to 2)
            down = link(SZShaders.DOWN, "uSource" to 0)
            up = link(SZShaders.UP, "uSource" to 0)
            present = link(SZShaders.PRESENT, "uLight" to 0, "uBloom" to 1)
            val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
            halfFloat = "GL_EXT_color_buffer_half_float" in extensions || "GL_EXT_color_buffer_float" in extensions
            paletteTexture = texture(GLES30.GL_RGBA8, 256, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_NEAREST)
            ready = true
        } catch (e: RuntimeException) {
            Log.e(TAG, "SynthezKit could not set up OpenGL", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        var upload: Boolean
        val dt: Double
        synchronized(lock) {
            if (hasPending) {
                val swap = front
                front = pending
                pending = swap
                hasPending = false
                upload = front.upload
                dt = front.dt
            } else {
                upload = false
                dt = 0.0
            }
        }
        val f = front
        val clear = if (f.screen != null) f.palette[f.background and 0xFF] else 0xFF000000.toInt()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClearColor(((clear shr 16) and 0xFF) / 255f, ((clear shr 8) and 0xFF) / 255f, (clear and 0xFF) / 255f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val screen = f.screen ?: return
        if (!ready || f.width == 0 || f.height == 0) return

        uploadPalette(f)
        if (indexWidth != f.width || indexHeight != f.height || indexTexture == 0) {
            if (indexTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(indexTexture), 0)
            indexTexture = texture(GLES30.GL_R8UI, f.width, f.height, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_NEAREST)
            indexWidth = f.width
            indexHeight = f.height
            indexBuffer = ByteBuffer.allocateDirect(f.width * f.height).order(ByteOrder.nativeOrder())
            upload = true
        }
        if (upload) {
            indexBuffer.clear()
            indexBuffer.put(f.pixels, 0, f.width * f.height)
            indexBuffer.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, indexTexture)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, f.width, f.height, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_BYTE, indexBuffer)
        }

        GLES30.glBindVertexArray(vertexArray)
        GLES30.glDisable(GLES30.GL_BLEND)
        if (f.crt.isEnabled) {
            drawCRT(f, screen, dt)
        } else {
            lightNeedsClear = true
            setViewport(screen)
            GLES30.glUseProgram(plain.id)
            bind(0, indexTexture)
            bind(1, paletteTexture)
            GLES30.glUniform2f(plain.uniform("uSize"), f.width.toFloat(), f.height.toFloat())
            GLES30.glUniform1f(plain.uniform("uScale"), screen.scale.toFloat())
            draw()
        }
    }

    private fun drawCRT(f: Frame, screen: SZScreen, dt: Double) {
        val crt = f.crt
        prepareCRTTargets(f.width, f.height, crt.spread.coerceIn(1, 6))
        if (lights.size < 2) return
        val previous = lights[lightSlot]
        lightSlot = lightSlot xor 1
        val current = lights[lightSlot]

        // 1. Phosphor.
        target(current)
        GLES30.glUseProgram(phosphor.id)
        bind(0, indexTexture)
        bind(1, paletteTexture)
        bind(2, previous.texture)
        // Red phosphor lingers longest; green and blue fade faster.
        val tau = max(crt.persistence, 0.0001)
        fun decay(factor: Double): Float = if (lightNeedsClear) 0f else exp(-dt / (tau * factor)).toFloat()
        // w = 0 marks the previous light as undefined (first frame).
        GLES30.glUniform4f(phosphor.uniform("uDecay"), decay(1.0), decay(0.7), decay(0.5), if (lightNeedsClear) 0f else 1f)
        draw()
        lightNeedsClear = false

        // 2. Halation: blur down the chain, then add each level back up.
        var source = current
        GLES30.glUseProgram(down.id)
        for (level in bloom) {
            target(level)
            bind(0, source.texture)
            GLES30.glUniform2f(down.uniform("uOutSize"), level.width.toFloat(), level.height.toFloat())
            draw()
            source = level
        }
        if (bloom.size > 1) {
            GLES30.glUseProgram(up.id)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            for (i in bloom.size - 1 downTo 1) {
                val into = bloom[i - 1]
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, into.framebuffer)
                GLES30.glViewport(0, 0, into.width, into.height)
                bind(0, bloom[i].texture)
                GLES30.glUniform2f(up.uniform("uOutSize"), into.width.toFloat(), into.height.toFloat())
                draw()
            }
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        // 3. Present.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        setViewport(screen)
        GLES30.glUseProgram(present.id)
        bind(0, current.texture)
        bind(1, (bloom.firstOrNull() ?: current).texture)
        GLES30.glUniform2f(present.uniform("uSize"), f.width.toFloat(), f.height.toFloat())
        GLES30.glUniform1f(present.uniform("uScale"), screen.scale.toFloat())
        GLES30.glUniform1f(present.uniform("uScanlines"), crt.scanlines.toFloat())
        GLES30.glUniform1f(present.uniform("uBeamDim"), crt.beamDim.toFloat())
        GLES30.glUniform1f(present.uniform("uBeamBright"), crt.beamBright.toFloat())
        GLES30.glUniform1f(present.uniform("uMaskStrength"), crt.maskStrength.toFloat())
        GLES30.glUniform1i(present.uniform("uMaskType"), crt.mask.ordinal)
        GLES30.glUniform1f(present.uniform("uHalation"), crt.halation.toFloat())
        GLES30.glUniform1f(present.uniform("uVignette"), crt.vignette.toFloat())
        GLES30.glUniform1f(present.uniform("uBrightness"), crt.brightness.toFloat())
        GLES30.glUniform1f(present.uniform("uAmbient"), crt.ambient.toFloat())
        GLES30.glUniform1f(present.uniform("uBloomLevels"), max(bloom.size, 1).toFloat())
        draw()
    }

    // Resources

    private fun uploadPalette(f: Frame) {
        if (f.paletteVersion == uploadedPalette) return
        uploadedPalette = f.paletteVersion
        paletteBuffer.clear()
        for (argb in f.palette) {
            paletteBuffer.put(((argb shr 16) and 0xFF).toByte())
            paletteBuffer.put(((argb shr 8) and 0xFF).toByte())
            paletteBuffer.put((argb and 0xFF).toByte())
            paletteBuffer.put(((argb ushr 24) and 0xFF).toByte())
        }
        paletteBuffer.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, paletteTexture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 256, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, paletteBuffer)
    }

    private fun prepareCRTTargets(width: Int, height: Int, levels: Int) {
        if (lights.firstOrNull()?.let { it.width != width || it.height != height } != false) {
            (lights + bloom).forEach(::release)
            lights = listOfNotNull(renderTarget(width, height), renderTarget(width, height))
            lightNeedsClear = true
            bloom = emptyList()
        }
        if (bloom.size != levels) {
            bloom.forEach(::release)
            bloom = (1..levels).mapNotNull { renderTarget(max(width shr it, 1), max(height shr it, 1)) }
        }
    }

    /** A linear-light render target: half float where the GPU can render to it, 8-bit otherwise. */
    private fun renderTarget(width: Int, height: Int): Target? {
        if (halfFloat) {
            makeTarget(width, height, GLES30.GL_RGBA16F, GLES30.GL_HALF_FLOAT)?.let { return it }
            halfFloat = false
        }
        return makeTarget(width, height, GLES30.GL_RGBA8, GLES30.GL_UNSIGNED_BYTE)
    }

    private fun makeTarget(width: Int, height: Int, internalFormat: Int, type: Int): Target? {
        val tex = texture(internalFormat, width, height, GLES30.GL_RGBA, type, GLES30.GL_LINEAR)
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ids[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (!complete) {
            GLES30.glDeleteFramebuffers(1, ids, 0)
            GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
            return null
        }
        return Target(tex, ids[0], width, height)
    }

    private fun release(target: Target) {
        GLES30.glDeleteFramebuffers(1, intArrayOf(target.framebuffer), 0)
        GLES30.glDeleteTextures(1, intArrayOf(target.texture), 0)
    }

    private fun texture(internalFormat: Int, width: Int, height: Int, format: Int, type: Int, filter: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, null)
        return ids[0]
    }

    private fun link(fragment: String, vararg samplers: Pair<String, Int>): Program {
        val vs = compile(GLES30.GL_VERTEX_SHADER, SZShaders.VERTEX)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
        val id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            GLES30.glDeleteProgram(id)
            throw IllegalStateException("Link failed: $log")
        }
        val program = Program(id)
        GLES30.glUseProgram(id)
        for ((name, unit) in samplers) GLES30.glUniform1i(program.uniform(name), unit)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("Compile failed: $log")
        }
        return shader
    }

    private fun bind(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    }

    private fun target(target: Target) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebuffer)
        GLES30.glViewport(0, 0, target.width, target.height)
    }

    /** The letterboxed viewport, flipped to GL's bottom-left origin. */
    private fun setViewport(screen: SZScreen) {
        val x = screen.viewportX.roundToInt()
        val w = screen.viewportWidth.roundToInt()
        val h = screen.viewportHeight.roundToInt()
        val y = surfaceHeight - (screen.viewportY + screen.viewportHeight).roundToInt()
        GLES30.glViewport(x, y, w, h)
    }

    private fun draw() {
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private companion object {
        const val TAG = "SynthezKit"
    }
}
