package com.photonlab.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
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
 * All tone adjustments are done in software (pixel-by-pixel) to match the AGSL shader logic
 * and work correctly on all API levels without requiring hardware acceleration.
 */
@Singleton
class EditPipeline @Inject constructor(
    @Suppress("UnusedPrivateParameter")
    @ApplicationContext private val context: Context,
) {
    fun process(source: Bitmap, state: EditState, lut: LutFile?): Bitmap {
        val rotated      = applyRotation(source, state.rotation)       // 90° steps: changes canvas
        val fineRotated  = applyFineRotation(rotated, state.fineRotation) // free rotation: fixed canvas
        val toneAdjusted = applyTone(fineRotated, state)
        val lutApplied   = if (lut != null) applyLut(toneAdjusted, lut) else toneAdjusted
        val sharpened    = if (state.sharpening > 0f) applySharpening(lutApplied, state.sharpening) else lutApplied
        val noised       = if (state.noise != 0f) applyNoise(sharpened, state.noise) else sharpened
        val cropped      = applyCrop(noised, state)
        return if (state.frameEnabled) applyFrame(cropped, state) else cropped
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
     * Excess pixels are cropped. This matches the "horizon straighten" behaviour.
     */
    private fun applyFineRotation(src: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return src
        val w  = src.width.toFloat()
        val h  = src.height.toFloat()
        val rad = Math.toRadians(degrees.toDouble())
        val c   = abs(cos(rad)).toFloat()
        val s   = abs(sin(rad)).toFloat()
        // Minimum scale so the rotated image fully covers the original w×h canvas
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
        return applyToneSoftware(src, state)
    }

    /** Pixel-by-pixel tone processor — identical logic to the AGSL shader. */
    private fun applyToneSoftware(src: Bitmap, state: EditState): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val exposure     = state.exposure
        val luminosity   = state.luminosity / 200f   // -100..100 → -0.5..0.5
        val contrast     = state.contrast / 100f     // -100..100 → -1..1
        // Asymmetric: negative /100 → full desaturation; positive /200 → moderate boost
        val saturation   = if (state.saturation >= 0f) state.saturation / 200f
                           else state.saturation / 100f
        val vibrance     = state.vibrance / 100f     // -100..100 → -1..1
        val temperature  = state.temperature / 100f  // -100..100 → -1..1
        val tint         = state.tint / 100f         // -100..100 → -1..1 (green→magenta)
        val highlights   = state.highlights / 200f   // -100..100 → -0.5..0.5
        val shadows      = state.shadows / 200f      // -100..100 → -0.5..0.5
        val expScale     = if (exposure != 0f) 2f.pow(exposure) else 1f

        for (i in pixels.indices) {
            val px = pixels[i]
            val a  = (px ushr 24) and 0xFF
            var r  = ((px shr 16) and 0xFF) / 255f
            var g  = ((px shr  8) and 0xFF) / 255f
            var b  = ( px         and 0xFF) / 255f

            // Exposure in linear light
            if (exposure != 0f) {
                r = linearToSrgb((srgbToLinear(r) * expScale).coerceIn(0f, 1f))
                g = linearToSrgb((srgbToLinear(g) * expScale).coerceIn(0f, 1f))
                b = linearToSrgb((srgbToLinear(b) * expScale).coerceIn(0f, 1f))
            }

            // Luminosity (uniform brightness offset)
            if (luminosity != 0f) {
                r = (r + luminosity).coerceIn(0f, 1f)
                g = (g + luminosity).coerceIn(0f, 1f)
                b = (b + luminosity).coerceIn(0f, 1f)
            }

            // Contrast around mid-grey
            if (contrast != 0f) {
                val sc = 1f + contrast
                r = ((r - 0.5f) * sc + 0.5f).coerceIn(0f, 1f)
                g = ((g - 0.5f) * sc + 0.5f).coerceIn(0f, 1f)
                b = ((b - 0.5f) * sc + 0.5f).coerceIn(0f, 1f)
            }

            // Highlights & Shadows (luma-masked)
            if (highlights != 0f || shadows != 0f) {
                val lum   = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val hMask = max(0f, (lum - 0.5f) * 2f)
                val sMask = max(0f, (0.5f - lum) * 2f)
                val adj   = highlights * hMask + shadows * sMask
                r = (r + adj).coerceIn(0f, 1f)
                g = (g + adj).coerceIn(0f, 1f)
                b = (b + adj).coerceIn(0f, 1f)
            }

            // Temperature (warm/cool) and Tint (green/magenta) — RGB adjustments
            if (temperature != 0f || tint != 0f) {
                r = (r + temperature * 0.15f).coerceIn(0f, 1f)
                g = (g - tint * 0.15f).coerceIn(0f, 1f)
                b = (b - temperature * 0.2f).coerceIn(0f, 1f)
            }

            // Saturation + Vibrance via HSL
            if (saturation != 0f || vibrance != 0f) {
                val maxC  = maxOf(r, g, b)
                val minC  = minOf(r, g, b)
                val delta = maxC - minC
                if (delta > 1e-5f) {
                    val l = (maxC + minC) * 0.5f
                    val s = delta / (1f - abs(2f * l - 1f))
                    // Apply saturation (additive, asymmetrically normalised)
                    var newS = (s + saturation).coerceIn(0f, 1f)
                    // Apply vibrance (fill-towards-max for positive, proportional for negative)
                    newS = if (vibrance >= 0f)
                        (newS + vibrance * (1f - newS)).coerceIn(0f, 1f)
                    else
                        (newS * (1f + vibrance)).coerceIn(0f, 1f)
                    val hRaw = when (maxC) {
                        r    -> (g - b) / delta
                        g    -> (b - r) / delta + 2f
                        else -> (r - g) / delta + 4f
                    }
                    val hue  = ((hRaw % 6f) + 6f) % 6f
                    val c2   = (1f - abs(2f * l - 1f)) * newS
                    val x    = c2 * (1f - abs(hue % 2f - 1f))
                    val m    = l - c2 * 0.5f
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
                 (b * 255f + 0.5f).toInt().coerceIn(0, 255)
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

    // ── Sharpening (Unsharp Mask, 3×3 box blur) ────────────────────────────────

    private fun applySharpening(src: Bitmap, strength: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Box-blur pass (3×3, clamp-to-edge)
        val blurred = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sr = 0; var sg = 0; var sb = 0
                var count = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = (x + dx).coerceIn(0, w - 1)
                    val ny = (y + dy).coerceIn(0, h - 1)
                    val p = pixels[ny * w + nx]
                    sr += (p shr 16) and 0xFF
                    sg += (p shr  8) and 0xFF
                    sb +=  p         and 0xFF
                    count++
                }
                val a = (pixels[y * w + x] ushr 24) and 0xFF
                blurred[y * w + x] = (a shl 24) or
                    ((sr / count) shl 16) or ((sg / count) shl 8) or (sb / count)
            }
        }

        // USM: out = original + factor * (original - blur)
        val factor = strength / 100f * 1.5f  // 0..1.5 at full strength
        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val a  = (pixels[i] ushr 24) and 0xFF
            val or_ = (pixels[i] shr 16) and 0xFF
            val og  = (pixels[i] shr  8) and 0xFF
            val ob  =  pixels[i]         and 0xFF
            val br  = (blurred[i] shr 16) and 0xFF
            val bg  = (blurred[i] shr  8) and 0xFF
            val bb  =  blurred[i]         and 0xFF
            val nr = (or_ + (factor * (or_ - br)).toInt()).coerceIn(0, 255)
            val ng = (og  + (factor * (og  - bg)).toInt()).coerceIn(0, 255)
            val nb = (ob  + (factor * (ob  - bb)).toInt()).coerceIn(0, 255)
            out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    // ── Noise (denoise / film grain) ──────────────────────────────────────────

    /**
     * noise < 0: denoise by blending with 3×3 box-blur  (strength = |noise|/100)
     * noise > 0: add luminance-preserving film grain     (amplitude = noise/100 × 50 counts)
     * Grain seed is fixed per image dimensions for a stable preview pattern.
     */
    private fun applyNoise(src: Bitmap, noise: Float): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        if (noise < 0f) {
            // Denoise: blend original with 3×3 box blur
            val factor = (-noise / 100f).coerceIn(0f, 1f)
            val out = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sr = 0; var sg = 0; var sb = 0; var count = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val p = pixels[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                        sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF; count++
                    }
                    val orig = pixels[y * w + x]
                    val a  = (orig ushr 24) and 0xFF
                    val or_ = (orig shr 16) and 0xFF
                    val og  = (orig shr  8) and 0xFF
                    val ob  =  orig         and 0xFF
                    val nr = (or_ + (factor * (sr / count - or_)).toInt()).coerceIn(0, 255)
                    val ng = (og  + (factor * (sg / count - og )).toInt()).coerceIn(0, 255)
                    val nb = (ob  + (factor * (sb / count - ob )).toInt()).coerceIn(0, 255)
                    out[y * w + x] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            result.setPixels(out, 0, w, 0, 0, w, h)
            return result
        } else {
            // Grain: add uniform random offset per pixel (fixed seed = stable preview)
            val maxOffset = (noise / 100f * 50f).toInt().coerceAtLeast(1)
            val rng = java.util.Random((w.toLong() * 31L + h) * 37L)
            for (i in pixels.indices) {
                val a  = (pixels[i] ushr 24) and 0xFF
                val r  = (pixels[i] shr 16)  and 0xFF
                val g  = (pixels[i] shr  8)  and 0xFF
                val b  =  pixels[i]           and 0xFF
                val d  = (rng.nextFloat() * 2f - 1f).let { (it * maxOffset).toInt() }
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

        val n = lut.size
        val nf = (n - 1).toFloat()
        val data = lut.data

        fun idx(ri: Int, gi: Int, bi: Int): Int = (bi * n * n + gi * n + ri) * 3

        for (i in pixels.indices) {
            val px = pixels[i]
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            val a = (px ushr 24) and 0xFF

            val rf = r * nf
            val gf = g * nf
            val bf = b * nf

            val r0 = rf.toInt().coerceIn(0, n - 2)
            val g0 = gf.toInt().coerceIn(0, n - 2)
            val b0 = bf.toInt().coerceIn(0, n - 2)
            val dr = rf - r0
            val dg = gf - g0
            val db = bf - b0

            fun sample(ri: Int, gi: Int, bi: Int, ch: Int): Float = data[idx(ri, gi, bi) + ch]
            fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

            fun ch(ch: Int): Float {
                val c000 = sample(r0, g0, b0, ch)
                val c001 = sample(r0, g0, b0 + 1, ch)
                val c010 = sample(r0, g0 + 1, b0, ch)
                val c011 = sample(r0, g0 + 1, b0 + 1, ch)
                val c100 = sample(r0 + 1, g0, b0, ch)
                val c101 = sample(r0 + 1, g0, b0 + 1, ch)
                val c110 = sample(r0 + 1, g0 + 1, b0, ch)
                val c111 = sample(r0 + 1, g0 + 1, b0 + 1, ch)
                return lerp(
                    lerp(lerp(c000, c001, db), lerp(c010, c011, db), dg),
                    lerp(lerp(c100, c101, db), lerp(c110, c111, db), dg),
                    dr
                )
            }

            val nr = (ch(0) * 255f).toInt().coerceIn(0, 255)
            val ng = (ch(1) * 255f).toInt().coerceIn(0, 255)
            val nb = (ch(2) * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    // ── Crop ──────────────────────────────────────────────────────────────────

    private fun applyCrop(src: Bitmap, state: EditState): Bitmap {
        val rect = state.cropRect ?: return src
        val x = (rect.left * src.width).toInt().coerceIn(0, src.width - 1)
        val y = (rect.top * src.height).toInt().coerceIn(0, src.height - 1)
        val w = (rect.width * src.width).toInt().coerceIn(1, src.width - x)
        val h = (rect.height * src.height).toInt().coerceIn(1, src.height - y)
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    // ── Frame ─────────────────────────────────────────────────────────────────

    private fun applyFrame(src: Bitmap, state: EditState): Bitmap {
        val srcW = src.width.toFloat()
        val srcH = src.height.toFloat()
        val srcRatio = srcW / srcH
        val targetRatio = if (state.frameRatio <= 0f) srcRatio else state.frameRatio

        // Canvas to fit src in target ratio (letterbox/pillarbox if ratios differ)
        val canvasW: Float
        val canvasH: Float
        if (srcRatio > targetRatio) {
            canvasW = srcW
            canvasH = srcW / targetRatio
        } else {
            canvasH = srcH
            canvasW = srcH * targetRatio
        }

        // Border in pixels
        val borderPx = (state.frameSizePct / 100f * minOf(canvasW, canvasH)).coerceAtLeast(0f)
        val outW = (canvasW + 2 * borderPx).toInt().coerceAtLeast(1)
        val outH = (canvasH + 2 * borderPx).toInt().coerceAtLeast(1)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(state.frameColor)
        // Draw src centered on the output canvas
        canvas.drawBitmap(src, (outW - srcW) / 2f, (outH - srcH) / 2f, null)
        return out
    }
}
