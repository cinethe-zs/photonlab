package com.photonlab.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import com.photonlab.R
import com.photonlab.domain.model.EditState
import com.photonlab.domain.model.LutFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Applies all edits from an [EditState] to a source [Bitmap] and returns a new [Bitmap].
 *
 * Tone adjustments use the GPU via AGSL [RuntimeShader] on API 33+ for maximum performance.
 * On older devices the same logic runs in software with a pre-computed LUT to eliminate
 * repeated pow() calls for the exposure step.
 */
@Singleton
class EditPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // AGSL shader source — loaded once from raw resources
    private val agslSource: String by lazy {
        context.resources.openRawResource(R.raw.edit_shader).bufferedReader().use { it.readText() }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Full pipeline: rotation → tone → LUT → sharpening → noise → crop → frame.
     * Equivalent to [processUpToFrame] + [applyFrame].
     */
    fun process(source: Bitmap, state: EditState, lut: LutFile?): Bitmap {
        val preFrame = processUpToFrame(source, state, lut)
        return if (state.frameEnabled) applyFrame(preFrame, state) else preFrame
    }

    /**
     * Runs the full pipeline and returns both the final result and the pre-frame bitmap
     * as a [Pair]. The second element (pre-frame) is used for histogram computation so
     * that frame border pixels don't contaminate the histogram.
     *
     * When [EditState.frameEnabled] is false both elements of the pair are the same object.
     */
    fun processAll(source: Bitmap, state: EditState, lut: LutFile?): Pair<Bitmap, Bitmap> {
        val preFrame = processUpToFrame(source, state, lut)
        return if (state.frameEnabled) Pair(applyFrame(preFrame, state), preFrame)
               else Pair(preFrame, preFrame)
    }

    /**
     * Runs all pipeline steps except the frame border. Useful when the caller needs
     * the unframed result (e.g. for histogram computation).
     */
    fun processUpToFrame(source: Bitmap, state: EditState, lut: LutFile?): Bitmap {
        val rotated     = applyRotation(source, state.rotation)
        val fineRotated = applyFineRotation(rotated, state.fineRotation)
        val toneAdj     = applyTone(fineRotated, state)
        val lutApplied  = if (lut != null) applyLut(toneAdj, lut) else toneAdj
        val sharpened   = if (state.sharpening > 0f) applySharpening(lutApplied, state.sharpening) else lutApplied
        val noised      = if (state.noise != 0f) applyNoise(sharpened, state.noise) else sharpened
        return applyCrop(noised, state)
    }

    // ── Rotation ───────────────────────────────────────────────────────────────

    /** 90° step rotation — expands/shrinks the canvas to fit the rotated bitmap. */
    private fun applyRotation(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /**
     * Free rotation — keeps the canvas exactly the same size as the input.
     * The image is scaled up (zoom-to-fill) so no black bars appear at the edges.
     */
    private fun applyFineRotation(src: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return src
        val w   = src.width.toFloat()
        val h   = src.height.toFloat()
        val rad = Math.toRadians(degrees.toDouble())
        val c   = abs(cos(rad)).toFloat()
        val s   = abs(sin(rad)).toFloat()
        val scale = max((w * c + h * s) / w, (w * s + h * c) / h)
        val matrix = Matrix().apply {
            setTranslate(-w / 2f, -h / 2f)
            postScale(scale, scale)
            postRotate(degrees)
            postTranslate(w / 2f, h / 2f)
        }
        val out    = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, matrix, null)
        return out
    }

    // ── Tone adjustments ───────────────────────────────────────────────────────

    private fun applyTone(src: Bitmap, state: EditState): Bitmap {
        if (state.exposure == 0f && state.luminosity == 0f && state.contrast == 0f &&
            state.highlights == 0f && state.shadows == 0f && state.saturation == 0f &&
            state.vibrance == 0f && state.temperature == 0f && state.tint == 0f
        ) return src

        // API 33+: use AGSL RuntimeShader (executed by Skia — much faster than Kotlin JVM)
        if (Build.VERSION.SDK_INT >= 33) {
            @Suppress("NewApi")
            applyToneAgsl(src, state)?.let { return it }
        }
        return applyToneSoftware(src, state)
    }

    /**
     * GPU-accelerated tone pass via AGSL RuntimeShader (API 33+).
     * Returns null if shader creation fails so the caller can fall back to software.
     */
    @Suppress("NewApi")
    private fun applyToneAgsl(src: Bitmap, state: EditState): Bitmap? = runCatching {
        val runtimeShader = android.graphics.RuntimeShader(agslSource)
        runtimeShader.setInputShader(
            "inputImage",
            BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
        )
        runtimeShader.setFloatUniform("exposure",    state.exposure)
        runtimeShader.setFloatUniform("luminosity",  state.luminosity / 200f)
        runtimeShader.setFloatUniform("contrast",    state.contrast / 100f)
        runtimeShader.setFloatUniform("highlights",  state.highlights / 200f)
        runtimeShader.setFloatUniform("shadows",     state.shadows / 200f)
        runtimeShader.setFloatUniform("saturation",
            if (state.saturation >= 0f) state.saturation / 200f else state.saturation / 100f)
        runtimeShader.setFloatUniform("vibrance",    state.vibrance / 100f)
        runtimeShader.setFloatUniform("temperature", state.temperature / 100f)
        runtimeShader.setFloatUniform("tint",        state.tint / 100f)

        val out    = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawPaint(Paint().apply { shader = runtimeShader })
        out
    }.getOrNull()

    /**
     * CPU tone pass — pixel-by-pixel, identical logic to the AGSL shader.
     *
     * Optimisations vs the naïve loop:
     * 1. Pre-computed 256-entry LUT eliminates pow() calls for exposure + luminosity + contrast.
     *    Fast path: if only those three are non-zero, apply the LUT with pure integer ops.
     * 2. Remaining parameters (highlights/shadows, temperature/tint, saturation/vibrance)
     *    are still per-pixel but only computed when their sliders are non-zero.
     */
    private fun applyToneSoftware(src: Bitmap, state: EditState): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val exposure    = state.exposure
        val luminosity  = state.luminosity / 200f
        val contrast    = state.contrast / 100f
        val saturation  = if (state.saturation >= 0f) state.saturation / 200f
                          else state.saturation / 100f
        val vibrance    = state.vibrance / 100f
        val temperature = state.temperature / 100f
        val tint        = state.tint / 100f
        val highlights  = state.highlights / 200f
        val shadows     = state.shadows / 200f
        val expScale    = if (exposure != 0f) 2f.pow(exposure) else 1f

        // Pre-compute LUT for the channel-independent ops (eliminates pow() per pixel)
        val hasBase       = exposure != 0f || luminosity != 0f || contrast != 0f
        val hasHighShadow = highlights != 0f || shadows != 0f
        val hasTemp       = temperature != 0f || tint != 0f
        val hasSatVib     = saturation != 0f || vibrance != 0f

        val baseLut: IntArray? = if (hasBase) IntArray(256) { i ->
            var v = i / 255f
            if (exposure    != 0f) v = linearToSrgb((srgbToLinear(v) * expScale).coerceIn(0f, 1f))
            if (luminosity  != 0f) v = (v + luminosity).coerceIn(0f, 1f)
            if (contrast    != 0f) v = ((v - 0.5f) * (1f + contrast) + 0.5f).coerceIn(0f, 1f)
            (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        } else null

        // Fast path: only exposure/luminosity/contrast — pure integer LUT, no float conversion
        if (baseLut != null && !hasHighShadow && !hasTemp && !hasSatVib) {
            for (i in pixels.indices) {
                val px = pixels[i]
                val a  = (px ushr 24) and 0xFF
                pixels[i] = (a shl 24) or
                    (baseLut[(px shr 16) and 0xFF] shl 16) or
                    (baseLut[(px shr  8) and 0xFF] shl  8) or
                    baseLut[px and 0xFF]
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            return out
        }

        // General path
        for (i in pixels.indices) {
            val px = pixels[i]
            val a  = (px ushr 24) and 0xFF

            // Apply base LUT (or raw values if no base adjustments)
            var r: Float
            var g: Float
            var b: Float
            if (baseLut != null) {
                r = baseLut[(px shr 16) and 0xFF] / 255f
                g = baseLut[(px shr  8) and 0xFF] / 255f
                b = baseLut[px          and 0xFF] / 255f
            } else {
                r = ((px shr 16) and 0xFF) / 255f
                g = ((px shr  8) and 0xFF) / 255f
                b = (px          and 0xFF) / 255f
            }

            // Highlights & Shadows (luma-masked)
            if (hasHighShadow) {
                val lum   = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val hMask = max(0f, (lum - 0.5f) * 2f)
                val sMask = max(0f, (0.5f - lum) * 2f)
                val adj   = highlights * hMask + shadows * sMask
                r = (r + adj).coerceIn(0f, 1f)
                g = (g + adj).coerceIn(0f, 1f)
                b = (b + adj).coerceIn(0f, 1f)
            }

            // Temperature & Tint
            if (hasTemp) {
                r = (r + temperature * 0.15f).coerceIn(0f, 1f)
                g = (g - tint       * 0.15f).coerceIn(0f, 1f)
                b = (b - temperature * 0.2f).coerceIn(0f, 1f)
            }

            // Saturation + Vibrance via HSL
            if (hasSatVib) {
                val maxC  = maxOf(r, g, b)
                val minC  = minOf(r, g, b)
                val delta = maxC - minC
                if (delta > 1e-5f) {
                    val l     = (maxC + minC) * 0.5f
                    val s     = delta / (1f - abs(2f * l - 1f))
                    var newS  = (s + saturation).coerceIn(0f, 1f)
                    newS = if (vibrance >= 0f)
                        (newS + vibrance * (1f - newS)).coerceIn(0f, 1f)
                    else
                        (newS * (1f + vibrance)).coerceIn(0f, 1f)
                    val hRaw = when (maxC) {
                        r    -> (g - b) / delta
                        g    -> (b - r) / delta + 2f
                        else -> (r - g) / delta + 4f
                    }
                    val hue = ((hRaw % 6f) + 6f) % 6f
                    val c2  = (1f - abs(2f * l - 1f)) * newS
                    val x   = c2 * (1f - abs(hue % 2f - 1f))
                    val m   = l - c2 * 0.5f
                    when {
                        hue < 1f -> { r = c2 + m; g = x  + m; b = m      }
                        hue < 2f -> { r = x  + m; g = c2 + m; b = m      }
                        hue < 3f -> { r = m;      g = c2 + m; b = x + m  }
                        hue < 4f -> { r = m;      g = x  + m; b = c2 + m }
                        hue < 5f -> { r = x  + m; g = m;      b = c2 + m }
                        else     -> { r = c2 + m; g = m;      b = x + m  }
                    }
                }
            }

            pixels[i] = (a shl 24) or
                ((r * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((g * 255f + 0.5f).toInt().coerceIn(0, 255) shl  8) or
                (b  * 255f + 0.5f).toInt().coerceIn(0, 255)
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

    // ── Sharpening (Unsharp Mask, separable 3-element blur) ────────────────────

    private fun applySharpening(src: Bitmap, strength: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Separable box blur: horizontal pass → temp, then vertical pass → blurred
        // 6 reads/pixel total vs 9 for a 2D 3×3 kernel
        val temp    = IntArray(w * h)
        val blurred = IntArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                val x0 = maxOf(0, x - 1); val x1 = minOf(w - 1, x + 1)
                val cnt = x1 - x0 + 1
                var sr = 0; var sg = 0; var sb = 0
                for (xi in x0..x1) {
                    val p = pixels[y * w + xi]
                    sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
                }
                val a = (pixels[y * w + x] ushr 24) and 0xFF
                temp[y * w + x] = (a shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
            }
        }

        // Vertical pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                val y0 = maxOf(0, y - 1); val y1 = minOf(h - 1, y + 1)
                val cnt = y1 - y0 + 1
                var sr = 0; var sg = 0; var sb = 0
                for (yi in y0..y1) {
                    val p = temp[yi * w + x]
                    sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
                }
                val a = (temp[y * w + x] ushr 24) and 0xFF
                blurred[y * w + x] = (a shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
            }
        }

        // USM: out = original + factor * (original - blur)
        val factor = strength / 100f * 1.5f
        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val a   = (pixels[i] ushr 24) and 0xFF
            val or_ = (pixels[i] shr 16)  and 0xFF
            val og  = (pixels[i] shr  8)  and 0xFF
            val ob  =  pixels[i]           and 0xFF
            val br  = (blurred[i] shr 16) and 0xFF
            val bg  = (blurred[i] shr  8) and 0xFF
            val bb  =  blurred[i]          and 0xFF
            out[i] = (a shl 24) or
                ((or_ + (factor * (or_ - br)).toInt()).coerceIn(0, 255) shl 16) or
                ((og  + (factor * (og  - bg)).toInt()).coerceIn(0, 255) shl  8) or
                (ob   + (factor * (ob  - bb)).toInt()).coerceIn(0, 255)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    // ── Noise (denoise / film grain) ──────────────────────────────────────────

    /**
     * noise < 0: denoise by blending with a separable 3×3 box-blur (strength = |noise|/100)
     * noise > 0: add luminance-preserving film grain (amplitude = noise/100 × 50 counts)
     * Grain seed is fixed per image dimensions for a stable preview pattern.
     */
    private fun applyNoise(src: Bitmap, noise: Float): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        if (noise < 0f) {
            // Denoise: blend original with separable box blur
            val factor = (-noise / 100f).coerceIn(0f, 1f)
            val temp   = IntArray(w * h)
            val blur   = IntArray(w * h)

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val x0 = maxOf(0, x - 1); val x1 = minOf(w - 1, x + 1); val cnt = x1 - x0 + 1
                    var sr = 0; var sg = 0; var sb = 0
                    for (xi in x0..x1) {
                        val p = pixels[y * w + xi]
                        sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
                    }
                    val a = (pixels[y * w + x] ushr 24) and 0xFF
                    temp[y * w + x] = (a shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
                }
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val y0 = maxOf(0, y - 1); val y1 = minOf(h - 1, y + 1); val cnt = y1 - y0 + 1
                    var sr = 0; var sg = 0; var sb = 0
                    for (yi in y0..y1) {
                        val p = temp[yi * w + x]
                        sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
                    }
                    blur[y * w + x] = (sr / cnt) shl 16 or ((sg / cnt) shl 8) or (sb / cnt)
                }
            }

            val out = IntArray(w * h)
            for (i in pixels.indices) {
                val orig = pixels[i]
                val a    = (orig ushr 24) and 0xFF
                val or_  = (orig shr 16) and 0xFF
                val og   = (orig shr  8) and 0xFF
                val ob   =  orig          and 0xFF
                val br   = (blur[i] shr 16) and 0xFF
                val bg   = (blur[i] shr  8) and 0xFF
                val bb   =  blur[i]          and 0xFF
                out[i] = (a shl 24) or
                    ((or_ + (factor * (br - or_)).toInt()).coerceIn(0, 255) shl 16) or
                    ((og  + (factor * (bg - og )).toInt()).coerceIn(0, 255) shl  8) or
                    (ob   + (factor * (bb - ob )).toInt()).coerceIn(0, 255)
            }
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            result.setPixels(out, 0, w, 0, 0, w, h)
            return result
        } else {
            // Grain
            val maxOffset = (noise / 100f * 50f).toInt().coerceAtLeast(1)
            val rng = java.util.Random((w.toLong() * 31L + h) * 37L)
            for (i in pixels.indices) {
                val a = (pixels[i] ushr 24) and 0xFF
                val r = (pixels[i] shr 16)  and 0xFF
                val g = (pixels[i] shr  8)  and 0xFF
                val b =  pixels[i]           and 0xFF
                val d = (rng.nextFloat() * 2f - 1f).let { (it * maxOffset).toInt() }
                pixels[i] = (a shl 24) or
                    ((r + d).coerceIn(0, 255) shl 16) or
                    ((g + d).coerceIn(0, 255) shl  8) or
                    (b + d).coerceIn(0, 255)
            }
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            result.setPixels(pixels, 0, w, 0, 0, w, h)
            return result
        }
    }

    // ── LUT (CPU trilinear interpolation) ─────────────────────────────────────

    private fun applyLut(src: Bitmap, lut: LutFile): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val n    = lut.size
        val nf   = (n - 1).toFloat()
        val data = lut.data

        fun idx(ri: Int, gi: Int, bi: Int): Int = (bi * n * n + gi * n + ri) * 3
        fun sample(ri: Int, gi: Int, bi: Int, ch: Int): Float = data[idx(ri, gi, bi) + ch]
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        for (i in pixels.indices) {
            val px = pixels[i]
            val a  = (px ushr 24) and 0xFF
            val rf = ((px shr 16) and 0xFF) / 255f * nf
            val gf = ((px shr  8) and 0xFF) / 255f * nf
            val bf = ( px         and 0xFF) / 255f * nf
            val r0 = rf.toInt().coerceIn(0, n - 2)
            val g0 = gf.toInt().coerceIn(0, n - 2)
            val b0 = bf.toInt().coerceIn(0, n - 2)
            val dr = rf - r0; val dg = gf - g0; val db = bf - b0

            fun ch(ch: Int): Float {
                return lerp(
                    lerp(lerp(sample(r0,   g0,   b0,   ch), sample(r0,   g0,   b0+1, ch), db),
                         lerp(sample(r0,   g0+1, b0,   ch), sample(r0,   g0+1, b0+1, ch), db), dg),
                    lerp(lerp(sample(r0+1, g0,   b0,   ch), sample(r0+1, g0,   b0+1, ch), db),
                         lerp(sample(r0+1, g0+1, b0,   ch), sample(r0+1, g0+1, b0+1, ch), db), dg),
                    dr,
                )
            }

            pixels[i] = (a shl 24) or
                ((ch(0) * 255f).toInt().coerceIn(0, 255) shl 16) or
                ((ch(1) * 255f).toInt().coerceIn(0, 255) shl  8) or
                (ch(2) * 255f).toInt().coerceIn(0, 255)
        }

        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    // ── Crop ──────────────────────────────────────────────────────────────────

    private fun applyCrop(src: Bitmap, state: EditState): Bitmap {
        val rect = state.cropRect ?: return src
        val x = (rect.left   * src.width ).toInt().coerceIn(0, src.width  - 1)
        val y = (rect.top    * src.height).toInt().coerceIn(0, src.height - 1)
        val w = (rect.width  * src.width ).toInt().coerceIn(1, src.width  - x)
        val h = (rect.height * src.height).toInt().coerceIn(1, src.height - y)
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    // ── Frame ─────────────────────────────────────────────────────────────────

    fun applyFrame(src: Bitmap, state: EditState): Bitmap {
        val srcW  = src.width.toFloat()
        val srcH  = src.height.toFloat()
        val srcRatio    = srcW / srcH
        val targetRatio = if (state.frameRatio <= 0f) srcRatio else state.frameRatio

        val canvasW: Float
        val canvasH: Float
        if (srcRatio > targetRatio) {
            canvasW = srcW; canvasH = srcW / targetRatio
        } else {
            canvasH = srcH; canvasW = srcH * targetRatio
        }

        val borderPx = (state.frameSizePct / 100f * minOf(canvasW, canvasH)).coerceAtLeast(0f)
        val outW     = (canvasW + 2 * borderPx).toInt().coerceAtLeast(1)
        val outH     = (canvasH + 2 * borderPx).toInt().coerceAtLeast(1)

        val out    = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(state.frameColor)
        canvas.drawBitmap(src, (outW - srcW) / 2f, (outH - srcH) / 2f, null)
        return out
    }
}
