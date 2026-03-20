package com.photonlab.rendering

import com.photonlab.domain.model.EditState
import com.photonlab.domain.model.LutFile
import com.photonlab.platform.DesktopBitmap
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Applies all edits from an [EditState] to a source [DesktopBitmap] and returns a new bitmap.
 *
 * Tone adjustments use the GPU via GLSL (LWJGL) for maximum performance.
 * Falls back to the identical CPU software path if the GPU is unavailable.
 *
 * All pixel-manipulation algorithms are identical to the Android EditPipeline.
 */
class EditPipeline {

    private val glslRenderer = GlslToneRenderer()

    // ── Public API ─────────────────────────────────────────────────────────

    fun process(source: DesktopBitmap, state: EditState, lut: LutFile?, photoDate: java.util.Date? = null): DesktopBitmap {
        val preFrame = processUpToFrame(source, state, lut, photoDate)
        return if (state.frameEnabled) applyFrame(preFrame, state) else preFrame
    }

    fun processAll(source: DesktopBitmap, state: EditState, lut: LutFile?, photoDate: java.util.Date? = null): Pair<DesktopBitmap, DesktopBitmap> {
        val preFrame = processUpToFrame(source, state, lut, photoDate)
        return if (state.frameEnabled) Pair(applyFrame(preFrame, state), preFrame)
               else Pair(preFrame, preFrame)
    }

    fun processUpToFrame(source: DesktopBitmap, state: EditState, lut: LutFile?, photoDate: java.util.Date? = null): DesktopBitmap {
        val rotated     = applyRotation(source, state.rotation)
        val fineRotated = applyFineRotation(rotated, state.fineRotation)
        val imprinted   = if (state.dateImprint.enabled && photoDate != null)
            DateImprintProcessor.burn(fineRotated, state.dateImprint, photoDate)
        else fineRotated
        val toneAdj     = applyTone(imprinted, state)
        val lutApplied  = if (lut != null) applyLut(toneAdj, lut) else toneAdj
        val sharpened   = if (state.sharpening > 0f) applySharpening(lutApplied, state.sharpening) else lutApplied
        val noised      = if (state.noise != 0f) applyNoise(sharpened, state.noise) else sharpened
        return applyCrop(noised, state)
    }

    fun destroy() = glslRenderer.destroy()

    // ── Rotation ───────────────────────────────────────────────────────────

    private fun applyRotation(src: DesktopBitmap, degrees: Int): DesktopBitmap {
        if (degrees == 0) return src
        return DesktopBitmap.createRotated90(src, degrees)
    }

    private fun applyFineRotation(src: DesktopBitmap, degrees: Float): DesktopBitmap {
        if (degrees == 0f) return src
        return DesktopBitmap.createFineRotated(src, degrees)
    }

    // ── Tone adjustments ───────────────────────────────────────────────────

    private fun applyTone(src: DesktopBitmap, state: EditState): DesktopBitmap {
        if (state.exposure == 0f && state.luminosity == 0f && state.contrast == 0f &&
            state.highlights == 0f && state.shadows == 0f && state.saturation == 0f &&
            state.vibrance == 0f && state.temperature == 0f && state.tint == 0f
        ) return src

        // Try GPU path first
        val uniforms = GlslToneRenderer.ToneUniforms(
            exposure    = state.exposure,
            luminosity  = state.luminosity / 200f,
            contrast    = state.contrast / 100f,
            saturation  = if (state.saturation >= 0f) state.saturation / 500f else state.saturation / 100f,
            vibrance    = state.vibrance / 100f,
            highlights  = state.highlights / 200f,
            shadows     = state.shadows / 200f,
            temperature = state.temperature / 100f,
            tint        = state.tint / 100f,
        )
        // runBlocking is safe here since this function is called from Dispatchers.Default
        val gpu = runBlocking { glslRenderer.render(src, uniforms) }
        if (gpu != null) return gpu

        return applyToneSoftware(src, state)
    }

    /**
     * CPU tone pass — pixel-by-pixel, identical logic to the AGSL/GLSL shader.
     */
    private fun applyToneSoftware(src: DesktopBitmap, state: EditState): DesktopBitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val exposure    = state.exposure
        val luminosity  = state.luminosity / 200f
        val contrast    = state.contrast / 100f
        val saturation  = if (state.saturation >= 0f) state.saturation / 500f
                          else state.saturation / 100f
        val vibrance    = state.vibrance / 100f
        val temperature = state.temperature / 100f
        val tint        = state.tint / 100f
        val highlights  = state.highlights / 200f
        val shadows     = state.shadows / 200f
        val expScale    = if (exposure != 0f) 2f.pow(exposure) else 1f

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

        // Fast path: only exposure/luminosity/contrast
        if (baseLut != null && !hasHighShadow && !hasTemp && !hasSatVib) {
            for (i in pixels.indices) {
                val px = pixels[i]
                val a  = (px ushr 24) and 0xFF
                pixels[i] = (a shl 24) or
                    (baseLut[(px shr 16) and 0xFF] shl 16) or
                    (baseLut[(px shr  8) and 0xFF] shl  8) or
                    baseLut[px and 0xFF]
            }
            val out = DesktopBitmap.create(w, h)
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            return out
        }

        // General path
        for (i in pixels.indices) {
            val px = pixels[i]
            val a  = (px ushr 24) and 0xFF
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

            if (hasHighShadow) {
                val lum   = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val hMask = max(0f, (lum - 0.5f) * 2f)
                val sMask = max(0f, (0.5f - lum) * 2f)
                val adj   = highlights * hMask + shadows * sMask
                r = (r + adj).coerceIn(0f, 1f)
                g = (g + adj).coerceIn(0f, 1f)
                b = (b + adj).coerceIn(0f, 1f)
            }

            if (hasTemp) {
                r = (r + temperature * 0.15f).coerceIn(0f, 1f)
                g = (g - tint       * 0.15f).coerceIn(0f, 1f)
                b = (b - temperature * 0.2f).coerceIn(0f, 1f)
            }

            if (hasSatVib) {
                val maxC  = maxOf(r, g, b)
                val minC  = minOf(r, g, b)
                val delta = maxC - minC
                if (delta > 1e-5f) {
                    val l     = (maxC + minC) * 0.5f
                    val s     = delta / (1f - abs(2f * l - 1f))
                    var newS  = (s + saturation).coerceIn(0f, 1f)
                    newS = if (vibrance >= 0f)
                        (newS + vibrance * 0.35f * (1f - newS) * (1f - newS * 0.5f)).coerceIn(0f, 1f)
                    else
                        (newS * (1f + vibrance * 0.35f)).coerceIn(0f, 1f)
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

        val out = DesktopBitmap.create(w, h)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

    // ── Sharpening (Unsharp Mask) ──────────────────────────────────────────

    private fun applySharpening(src: DesktopBitmap, strength: Float): DesktopBitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h); src.getPixels(pixels, 0, w, 0, 0, w, h)
        val temp    = IntArray(w * h)
        val blurred = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val x0 = maxOf(0, x - 1); val x1 = minOf(w - 1, x + 1); val cnt = x1 - x0 + 1
                var sr = 0; var sg = 0; var sb = 0
                for (xi in x0..x1) { val p = pixels[y * w + xi]; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF }
                val a = (pixels[y * w + x] ushr 24) and 0xFF
                temp[y * w + x] = (a shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val y0 = maxOf(0, y - 1); val y1 = minOf(h - 1, y + 1); val cnt = y1 - y0 + 1
                var sr = 0; var sg = 0; var sb = 0
                for (yi in y0..y1) { val p = temp[yi * w + x]; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF }
                val a = (temp[y * w + x] ushr 24) and 0xFF
                blurred[y * w + x] = (a shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
            }
        }

        val factor = strength / 100f * 1.5f
        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val a   = (pixels[i] ushr 24) and 0xFF
            val or_ = (pixels[i] shr 16)  and 0xFF; val og = (pixels[i] shr 8) and 0xFF; val ob = pixels[i] and 0xFF
            val br  = (blurred[i] shr 16) and 0xFF; val bg = (blurred[i] shr 8) and 0xFF; val bb = blurred[i] and 0xFF
            out[i] = (a shl 24) or
                ((or_ + (factor * (or_ - br)).toInt()).coerceIn(0, 255) shl 16) or
                ((og  + (factor * (og  - bg)).toInt()).coerceIn(0, 255) shl  8) or
                (ob   + (factor * (ob  - bb)).toInt()).coerceIn(0, 255)
        }
        val result = DesktopBitmap.create(w, h); result.setPixels(out, 0, w, 0, 0, w, h); return result
    }

    // ── Noise (denoise / film grain) ──────────────────────────────────────

    private fun applyNoise(src: DesktopBitmap, noise: Float): DesktopBitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h); src.getPixels(pixels, 0, w, 0, 0, w, h)

        if (noise < 0f) {
            val factor = (-noise / 100f).coerceIn(0f, 1f)
            val temp = IntArray(w * h); val blur = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val x0 = maxOf(0, x-1); val x1 = minOf(w-1, x+1); val cnt = x1-x0+1
                var sr = 0; var sg = 0; var sb = 0
                for (xi in x0..x1) { val p = pixels[y*w+xi]; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF }
                val a = (pixels[y*w+x] ushr 24) and 0xFF
                temp[y*w+x] = (a shl 24) or ((sr/cnt) shl 16) or ((sg/cnt) shl 8) or (sb/cnt)
            }
            for (y in 0 until h) for (x in 0 until w) {
                val y0 = maxOf(0, y-1); val y1 = minOf(h-1, y+1); val cnt = y1-y0+1
                var sr = 0; var sg = 0; var sb = 0
                for (yi in y0..y1) { val p = temp[yi*w+x]; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF }
                blur[y*w+x] = (sr/cnt) shl 16 or ((sg/cnt) shl 8) or (sb/cnt)
            }
            val out = IntArray(w * h)
            for (i in pixels.indices) {
                val orig = pixels[i]; val a = (orig ushr 24) and 0xFF
                val or_ = (orig shr 16) and 0xFF; val og = (orig shr 8) and 0xFF; val ob = orig and 0xFF
                val br  = (blur[i] shr 16) and 0xFF; val bg = (blur[i] shr 8) and 0xFF; val bb = blur[i] and 0xFF
                out[i] = (a shl 24) or ((or_ + (factor * (br - or_)).toInt()).coerceIn(0,255) shl 16) or
                         ((og + (factor * (bg - og)).toInt()).coerceIn(0,255) shl 8) or
                         (ob + (factor * (bb - ob)).toInt()).coerceIn(0,255)
            }
            val result = DesktopBitmap.create(w, h); result.setPixels(out, 0, w, 0, 0, w, h); return result
        } else {
            return applyFilmGrain(src, noise)
        }
    }

    private fun applyFilmGrain(src: DesktopBitmap, strength: Float): DesktopBitmap {
        val w = src.width; val h = src.height; val n = w * h
        val pixels = IntArray(n); src.getPixels(pixels, 0, w, 0, 0, w, h)
        val amp = strength / 100f; val grainAmp = amp * 16f
        val blurR = if (amp < 0.5f) 1 else 2; val blurD = (2 * blurR + 1).toFloat()

        var xs1 = (w.toLong() * 1664525L xor h.toLong() * 22695477L xor 0x3141592653589793L) or 1L
        var xs2 = (xs1 * 6364136223846793005L + 1442695040888963407L) or 1L
        fun rng1(): Long { xs1 = xs1 xor (xs1 shl 13); xs1 = xs1 xor (xs1 ushr 7); xs1 = xs1 xor (xs1 shl 17); return xs1 }
        fun rng2(): Long { xs2 = xs2 xor (xs2 shl 7); xs2 = xs2 xor (xs2 ushr 9); xs2 = xs2 xor (xs2 shl 8); return xs2 }
        fun f1() = (rng1() and 0xFFFFFFL).toFloat() / 0x800000.toFloat() - 1f
        fun f2() = (rng2() and 0xFFFFFFL).toFloat() / 0x800000.toFloat() - 1f
        fun gauss1() = (f1() + f1() + f1()) * 0.5774f
        fun gauss2() = (f2() + f2() + f2()) * 0.5774f

        val lumaG   = FloatArray(n) { gauss1() }
        val chromaG = FloatArray(n) { gauss2() * 0.35f }
        val tempL = FloatArray(n); val tempC = FloatArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = maxOf(0, x-blurR); val x1 = minOf(w-1, x+blurR); val cnt = (x1-x0+1).toFloat()
            var sL = 0f; var sC = 0f
            for (xi in x0..x1) { val idx = y*w+xi; sL += lumaG[idx]; sC += chromaG[idx] }
            tempL[y*w+x] = sL/cnt; tempC[y*w+x] = sC/cnt
        }
        for (y in 0 until h) for (x in 0 until w) {
            val y0 = maxOf(0, y-blurR); val y1 = minOf(h-1, y+blurR); val cnt = (y1-y0+1).toFloat()
            var sL = 0f; var sC = 0f
            for (yi in y0..y1) { val idx = yi*w+x; sL += tempL[idx]; sC += tempC[idx] }
            lumaG[y*w+x] = sL/cnt; chromaG[y*w+x] = sC/cnt
        }
        for (i in pixels.indices) {
            val px = pixels[i]; val a = (px ushr 24) and 0xFF
            val ri = (px shr 16) and 0xFF; val gi = (px shr 8) and 0xFF; val bi = px and 0xFF
            val lum = (0.2126f * ri + 0.7152f * gi + 0.0722f * bi) / 255f
            val l = lum.coerceIn(0f, 1f)
            val lumMask = if (l < 0.5f) { val t = l*2f; 0.1f + t*(2f-t)*0.9f } else { val t = (l-0.5f)*2f; 0.1f + (1f - t*t)*0.9f }
            val gL = lumaG[i] * blurD * blurD * grainAmp * lumMask
            val gC = chromaG[i] * blurD * blurD * grainAmp * lumMask
            val dR = (gL + gC * 0.7f).toInt(); val dG = gL.toInt(); val dB = (gL - gC * 0.7f).toInt()
            pixels[i] = (a shl 24) or ((ri+dR).coerceIn(0,255) shl 16) or ((gi+dG).coerceIn(0,255) shl 8) or (bi+dB).coerceIn(0,255)
        }
        val result = DesktopBitmap.create(w, h); result.setPixels(pixels, 0, w, 0, 0, w, h); return result
    }

    // ── LUT (trilinear interpolation) ─────────────────────────────────────

    private fun applyLut(src: DesktopBitmap, lut: LutFile): DesktopBitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h); src.getPixels(pixels, 0, w, 0, 0, w, h)
        val n = lut.size; val nf = (n - 1).toFloat(); val data = lut.data
        fun idx(ri: Int, gi: Int, bi: Int): Int = (bi * n * n + gi * n + ri) * 3
        fun sample(ri: Int, gi: Int, bi: Int, ch: Int): Float = data[idx(ri, gi, bi) + ch]
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        for (i in pixels.indices) {
            val px = pixels[i]; val a = (px ushr 24) and 0xFF
            val rf = ((px shr 16) and 0xFF) / 255f * nf
            val gf = ((px shr  8) and 0xFF) / 255f * nf
            val bf = ( px         and 0xFF) / 255f * nf
            val r0 = rf.toInt().coerceIn(0, n-2); val g0 = gf.toInt().coerceIn(0, n-2); val b0 = bf.toInt().coerceIn(0, n-2)
            val dr = rf - r0; val dg = gf - g0; val db = bf - b0
            fun ch(ch: Int) = lerp(
                lerp(lerp(sample(r0,g0,b0,ch),sample(r0,g0,b0+1,ch),db), lerp(sample(r0,g0+1,b0,ch),sample(r0,g0+1,b0+1,ch),db), dg),
                lerp(lerp(sample(r0+1,g0,b0,ch),sample(r0+1,g0,b0+1,ch),db), lerp(sample(r0+1,g0+1,b0,ch),sample(r0+1,g0+1,b0+1,ch),db), dg),
                dr)
            pixels[i] = (a shl 24) or ((ch(0)*255f).toInt().coerceIn(0,255) shl 16) or ((ch(1)*255f).toInt().coerceIn(0,255) shl 8) or (ch(2)*255f).toInt().coerceIn(0,255)
        }
        val out = DesktopBitmap.create(w, h); out.setPixels(pixels, 0, w, 0, 0, w, h); return out
    }

    // ── Crop ──────────────────────────────────────────────────────────────

    private fun applyCrop(src: DesktopBitmap, state: EditState): DesktopBitmap {
        val rect = state.cropRect ?: return src
        val x = (rect.left   * src.width ).toInt().coerceIn(0, src.width  - 1)
        val y = (rect.top    * src.height).toInt().coerceIn(0, src.height - 1)
        val w = (rect.width  * src.width ).toInt().coerceIn(1, src.width  - x)
        val h = (rect.height * src.height).toInt().coerceIn(1, src.height - y)
        return DesktopBitmap.createSubset(src, x, y, w, h)
    }

    // ── Frame ─────────────────────────────────────────────────────────────

    fun applyFrame(src: DesktopBitmap, state: EditState): DesktopBitmap {
        val srcW = src.width.toFloat(); val srcH = src.height.toFloat()
        val srcRatio    = srcW / srcH
        val targetRatio = if (state.frameRatio <= 0f) srcRatio else state.frameRatio
        val canvasW: Float; val canvasH: Float
        if (srcRatio > targetRatio) { canvasW = srcW; canvasH = srcW / targetRatio }
        else                        { canvasH = srcH; canvasW = srcH * targetRatio  }
        val borderPx = (state.frameSizePct / 100f * minOf(canvasW, canvasH)).coerceAtLeast(0f)
        val outW = (canvasW + 2 * borderPx).toInt().coerceAtLeast(1)
        val outH = (canvasH + 2 * borderPx).toInt().coerceAtLeast(1)

        val out   = DesktopBitmap.create(outW, outH)
        val g2d   = out.image.createGraphics()
        g2d.color = java.awt.Color(state.frameColor, true)
        g2d.fillRect(0, 0, outW, outH)
        g2d.drawImage(src.image, ((outW - srcW) / 2f).toInt(), ((outH - srcH) / 2f).toInt(), null)
        g2d.dispose()
        return out
    }
}
