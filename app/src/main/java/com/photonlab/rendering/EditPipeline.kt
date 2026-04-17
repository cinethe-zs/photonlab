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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
    private val agslSource: String by lazy {
        context.resources.openRawResource(R.raw.edit_shader).bufferedReader().use { it.readText() }
    }

    private val intArrayPool = object {
        private var pixels: IntArray? = null
        private var temp: IntArray? = null
        private var temp2: IntArray? = null
        private var out: IntArray? = null
        private var lastSize = 0
        private val lock = Any()

        fun getIntArrays(size: Int): Array<IntArray> {
            if (size <= 0) return arrayOf(IntArray(1), IntArray(1), IntArray(1), IntArray(1))
            synchronized(lock) {
                if (size != lastSize) {
                    pixels = null; temp = null; temp2 = null; out = null
                    lastSize = size
                }
                val p = pixels ?: IntArray(size).also { pixels = it }
                val t = temp ?: IntArray(size).also { temp = it }
                val t2 = temp2 ?: IntArray(size).also { temp2 = it }
                val o = out ?: IntArray(size).also { out = it }
                return arrayOf(p, t, t2, o)
            }
        }

        fun clear() {
            synchronized(lock) {
                pixels = null; temp = null; temp2 = null; out = null; lastSize = 0
            }
        }
    }

    private val floatArrayPool = object {
        private var lumaG: FloatArray? = null
        private var chromaG: FloatArray? = null
        private var tempL: FloatArray? = null
        private var tempC: FloatArray? = null
        private var lastSize = 0
        private val lock = Any()

        fun getFloatArrays(size: Int): Array<FloatArray> {
            if (size <= 0) return arrayOf(FloatArray(1), FloatArray(1), FloatArray(1), FloatArray(1))
            synchronized(lock) {
                if (size != lastSize) {
                    lumaG = null; chromaG = null; tempL = null; tempC = null
                    lastSize = size
                }
                val lg = lumaG ?: FloatArray(size).also { lumaG = it }
                val cg = chromaG ?: FloatArray(size).also { chromaG = it }
                val tl = tempL ?: FloatArray(size).also { tempL = it }
                val tc = tempC ?: FloatArray(size).also { tempC = it }
                return arrayOf(lg, cg, tl, tc)
            }
        }

        fun clear() {
            synchronized(lock) {
                lumaG = null; chromaG = null; tempL = null; tempC = null; lastSize = 0
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    companion object {
        private const val TILE_HEIGHT_PIXELS = 256
        private const val LARGE_IMAGE_THRESHOLD = 5 * 1024 * 1024
    }

    /**
     * Full pipeline: rotation → tone → LUT → sharpening → noise → crop → frame.
     * Equivalent to [processUpToFrame] + [applyFrame].
     *
     * For images >5MP, uses tile-based processing to limit peak memory usage.
     * Tiling processes the image in small horizontal strips, reducing the size of
     * intermediate bitmaps in the processing pipeline.
     */
fun process(source: Bitmap, state: EditState, lut: LutFile?, date: java.util.Date = java.util.Date()): Bitmap {
 val preFrame = if (shouldUseTiling(source, state)) {
 processUpToFrameTiled(source, state, lut, date)
 } else {
 processUpToFrame(source, state, lut, date)
 }
 return if (state.frameEnabled) applyFrame(preFrame, state) else preFrame
 }

private fun shouldUseTiling(source: Bitmap, state: EditState): Boolean {
		if (source.width <= 0 || source.height <= 0) return false
		if (source.width * source.height <= LARGE_IMAGE_THRESHOLD) return false
		// Allow tiling even with rotation - we'll handle it specially in tiled processing
		return true
	}

/**
 * Tile-based processing for large images. Processes the image in horizontal
 * strips (256px height) to limit peak memory usage. Each tile goes through
 * the pipeline except date imprint (applied once to full output).
 *
 * For rotated images, rotation is applied BEFORE tiling to create a single
 * intermediate rotated bitmap, then tiling operates on that rotated image.
 * This limits memory usage even for large rotated images.
 */
private fun processUpToFrameTiled(source: Bitmap, state: EditState, lut: LutFile?, date: java.util.Date): Bitmap {
    // BUG FIX: cropRect is in normalized (0-1) coords for the ORIGINAL image.
    // After 90°/270° rotation, dimensions are swapped so we must transform
    // cropRect BEFORE applying crop. Otherwise the same normalized coords map
    // to a different area. E.g. 90° clockwise: left→top, top→right, dims swap.
    //
    // For fine rotation (no step rotation), rotation must be applied BEFORE crop
    // to match the non-tiled preview path. This is because fine rotation doesn't
    // swap dimensions, so the crop rect doesn't need transforming but DOES need
    // to be applied to the rotated image (not the original).

    val hasFineRotationOnly = state.fineRotation != 0f && state.rotation == 0
    val effectiveRotation = if (hasFineRotationOnly) 0 else state.rotation

    // For 90/180/270 with crop: transform crop rect first (dims swap)
    // For fine rotation with crop: rotate full image first, then crop
    // For crop only (no rotation): crop directly
val preProcessed: Bitmap
 val toRecyclePre = if (hasFineRotationOnly) {
 // Fine rotation path: apply fine rotation first (then crop if cropRect is set)
 val rotated = if (state.fineRotation != 0f) {
 applyFineRotation(source, state.fineRotation)
 } else source
 preProcessed = if (state.cropRect != null) {
 val cropped = applyCrop(rotated, state.copy(fineRotation = 0f))
 if (cropped !== rotated) rotated.recycle()
 cropped
} else rotated
    if (preProcessed !== source) preProcessed else null
} else {
        // 90/180/270: rotate first, then crop (like preview path)
        // The crop will be applied after rotation below
        preProcessed = source
        null // toRecyclePre - nothing separate from source
    }

// Apply step rotation if needed (90/180/270)
    var rotatedSource: Bitmap
    if (state.rotation != 0) {
        val result = applyRotation(preProcessed, state.rotation)
        if (result !== preProcessed && preProcessed !== source) preProcessed.recycle()
        rotatedSource = result
    } else {
        rotatedSource = preProcessed
    }

// Apply fine rotation AFTER step rotation when both are present
// (skip if hasFineRotationOnly - fine rotation already applied to full image earlier)
if (state.fineRotation != 0f && !hasFineRotationOnly) {
        val fineResult = applyFineRotation(rotatedSource, state.fineRotation)
        if (fineResult !== rotatedSource) {
            if (rotatedSource !== preProcessed && rotatedSource !== source) rotatedSource.recycle()
            rotatedSource = fineResult
        }
    }

    // Apply crop AFTER rotation (like preview path)
    val croppedSource: Bitmap
    val toRecycleForCrop: Bitmap?
    if (state.cropRect != null) {
        croppedSource = applyCrop(rotatedSource, state.copy(fineRotation = 0f))
        toRecycleForCrop = if (croppedSource !== rotatedSource) rotatedSource else null
    } else {
        croppedSource = rotatedSource
        toRecycleForCrop = null
    }

// Now croppedSource is what we tile
    val totalHeight = croppedSource.height
    val width = croppedSource.width

if (width <= 0 || totalHeight <= 0) {
        toRecyclePre?.recycle()
        toRecycleForCrop?.recycle()
        return source
    }

    val numTiles = ceil(totalHeight.toFloat() / TILE_HEIGHT_PIXELS).toInt()
    if (numTiles <= 0) {
        toRecyclePre?.recycle()
        toRecycleForCrop?.recycle()
        return source
    }

    val output = try {
        Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
    } catch (e: Throwable) {
        toRecyclePre?.recycle()
        toRecycleForCrop?.recycle()
        return source
    }

		val tileBufferSize = width * TILE_HEIGHT_PIXELS
		val arrays = intArrayPool.getIntArrays(tileBufferSize.coerceAtLeast(width))

		// Process tiles using rotatedSource as the base (no crop or rotation in tile processing)
		try {
			for (tileIndex in 0 until numTiles) {
				val startY = tileIndex * TILE_HEIGHT_PIXELS
				val endY = min(startY + TILE_HEIGHT_PIXELS, totalHeight)
				val tileHeight = endY - startY

				if (tileHeight <= 0) continue

// Tiles are created from cropped source, so coordinates are valid
    val tile = Bitmap.createBitmap(croppedSource, 0, startY, width, tileHeight)
val processedTile = try {
        // Process tile WITHOUT applying crop/rotation (already applied to croppedSource)
        // Pass rotation=0 and fineRotation=0f since rotation was already applied to full image
        processUpToFrameNoDateImprintNoCrop(tile, state.copy(rotation = 0, fineRotation = 0f, cropRect = null), lut)
    } catch (e: Throwable) {
        tile.recycle()
        output.recycle()
        toRecyclePre?.recycle()
        toRecycleForCrop?.recycle()
        throw e
    }

    try {
        val pixels = arrays[0]
        processedTile.getPixels(pixels, 0, processedTile.width, 0, 0, processedTile.width, tileHeight)
        output.setPixels(pixels, 0, processedTile.width, 0, startY, processedTile.width, tileHeight)
    } catch (e: Throwable) {
        if (processedTile !== tile) processedTile.recycle()
        tile.recycle()
        output.recycle()
        toRecyclePre?.recycle()
        toRecycleForCrop?.recycle()
        throw e
    }

if (processedTile !== tile) processedTile.recycle()
        tile.recycle()
    }

    // Clean up intermediate bitmaps
    toRecyclePre?.recycle()
    toRecycleForCrop?.recycle()

    // Apply date imprint at the end to the merged result
			if (state.dateImprint.enabled) {
				try {
					val result = DateImprintProcessor.burn(output, state.dateImprint, date, context)
					if (result !== output) output.recycle()
					return result
				} catch (e: Throwable) {
					output.recycle()
					throw e
				}
			}
		} catch (e: Throwable) {
			output.recycle()
			throw e
		}

		return output
	}

/**
     * Process pipeline without date imprint (used for tiled processing).
     * Note: Does not recycle any intermediate bitmaps - the caller (processUpToFrameTiled)
     * handles recycling the original tile. For small 256px tiles the memory overhead is acceptable.
     */
    private fun processUpToFrameNoDateImprint(source: Bitmap, state: EditState, lut: LutFile?): Bitmap {
        var result = applyRotation(source, state.rotation)
        result = applyFineRotation(result, state.fineRotation)
        result = applyTone(result, state)
        result = if (lut != null) applyLut(result, lut) else result
        result = if (state.sharpening > 0f) applySharpening(result, state.sharpening) else result
        result = if (state.noise != 0f) applyNoise(result, state.noise) else result
        return applyCrop(result, state)
    }

    /**
     * Process pipeline without date imprint AND without crop.
     * Used for tiled processing where crop is applied BEFORE tiling to the full source image.
     */
    private fun processUpToFrameNoDateImprintNoCrop(source: Bitmap, state: EditState, lut: LutFile?): Bitmap {
        var result = applyRotation(source, state.rotation)
        result = applyFineRotation(result, state.fineRotation)
        result = applyTone(result, state)
        result = if (lut != null) applyLut(result, lut) else result
        result = if (state.sharpening > 0f) applySharpening(result, state.sharpening) else result
        result = if (state.noise != 0f) applyNoise(result, state.noise) else result
        return result
    }

    /**
     * Runs the full pipeline and returns both the final result and the pre-frame bitmap
     * as a [Pair]. The second element (pre-frame) is used for histogram computation so
     * that frame border pixels don't contaminate the histogram.
     *
     * When [EditState.frameEnabled] is false both elements of the pair are the same object.
     */
    fun processAll(source: Bitmap, state: EditState, lut: LutFile?, date: java.util.Date = java.util.Date()): Pair<Bitmap, Bitmap> {
        return try {
            val preFrame = if (shouldUseTiling(source, state)) {
                processUpToFrameTiled(source, state, lut, date)
            } else {
                processUpToFrame(source, state, lut, date)
            }
            if (state.frameEnabled) Pair(applyFrame(preFrame, state), preFrame)
            else Pair(preFrame, preFrame)
} catch (e: Throwable) {
        // Return a safe fallback - create a copy of source to avoid recycled bitmap issues
        val fallback = source.copy(Bitmap.Config.ARGB_8888, true)
        Pair(fallback, fallback)
    }
    }

/**
 * Runs all pipeline steps except the frame border. Useful when the caller needs
 * the unframed result (e.g. for histogram computation).
 */
fun processUpToFrame(source: Bitmap, state: EditState, lut: LutFile?, date: java.util.Date = java.util.Date()): Bitmap {
  val toRecycle = mutableListOf<Bitmap>()
  var prev = source
  var result = source
  try {
    val rotated = applyRotation(source, state.rotation)
    if (rotated !== source) { toRecycle.add(source); result = rotated }
    prev = result

    val fineRotated = applyFineRotation(prev, state.fineRotation)
    if (fineRotated !== prev) { toRecycle.add(prev); result = fineRotated }
    prev = result

val toneAdj = applyTone(prev, state)
    if (toneAdj !== prev) { toRecycle.add(prev); result = toneAdj }
    prev = result

    val lutResult = if (lut != null) {
        val r = applyLut(prev, lut)
        if (r !== prev) { toRecycle.add(prev); result = r }
        r
    } else result
    prev = lutResult

    val sharpened = if (state.sharpening > 0f) {
        val r = applySharpening(prev, state.sharpening)
        if (r !== prev) { toRecycle.add(prev); result = r }
        r
    } else prev
    prev = sharpened

    val noised = if (state.noise != 0f) {
        val r = applyNoise(prev, state.noise)
        if (r !== prev) { toRecycle.add(prev); result = r }
        r
    } else prev
    prev = noised

    val cropped = applyCrop(prev, state)
    if (cropped !== prev) { toRecycle.add(prev) }
    prev = cropped

    // Apply date imprint AFTER all other adjustments so it's preserved
    // even when applyTone returns the original source (early return when all sliders are 0)
    val final = if (state.dateImprint.enabled) {
        val r = DateImprintProcessor.burn(prev, state.dateImprint, date, context)
        if (r !== prev) { toRecycle.add(prev); result = r }
        r
    } else prev
    return final
  } catch (e: Throwable) {
    toRecycle.forEach { it.recycle() }
    throw e
  }
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
    // NOTE: We intentionally do NOT early-return when all sliders are at 0.
    // The early return would break the bitmap chain because applyTone would return
    // the original source instead of a new bitmap, discarding any prior modifications
    // (like date imprint). Both applyToneAgsl and applyToneSoftware create new bitmaps
    // even when no adjustments are needed, preserving the chain.

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
            if (state.saturation >= 0f) state.saturation / 500f else state.saturation / 100f)
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
        val saturation  = if (state.saturation >= 0f) state.saturation / 500f
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
        val n = w * h

        val arrays = intArrayPool.getIntArrays(n)
        val pixels = arrays[0]
        val temp = arrays[1]
        val blurred = arrays[2]
        val out = arrays[3]

        src.getPixels(pixels, 0, w, 0, 0, w, h)

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

        val factor = strength / 100f * 1.5f
        for (i in 0 until n) {
            val a   = (pixels[i] ushr 24) and 0xFF
            val or_ = (pixels[i] shr 16)  and 0xFF
            val og  = (pixels[i] shr  8)  and 0xFF
            val ob  =  pixels[i]           and 0xFF
            val br = (blurred[i] shr 16) and 0xFF
            val bg = (blurred[i] shr  8) and 0xFF
            val bb =  blurred[i]          and 0xFF
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
     * noise < 0: denoise — blend with a separable 3×3 box-blur
     * noise > 0: procedural film grain (see [applyFilmGrain])
     */
    private fun applyNoise(src: Bitmap, noise: Float): Bitmap {
        val w = src.width; val h = src.height
        val n = w * h

        if (noise < 0f) {
            val arrays = intArrayPool.getIntArrays(n)
            val pixels = arrays[0]
            val temp = arrays[1]
            val blur = arrays[2]
            val out = arrays[3]

            src.getPixels(pixels, 0, w, 0, 0, w, h)

            val factor = (-noise / 100f).coerceIn(0f, 1f)

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

            for (i in 0 until n) {
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
            return applyFilmGrain(src, noise)
        }
    }

    /**
     * Procedural film grain generator modelled on real scanned silver-halide film.
     *
     * Key properties vs the previous uniform-noise approach:
     *
     * 1. **Gaussian distribution** — silver crystal exposure follows a normal distribution.
     *    Implemented via CLT (sum of 3 uniforms ≈ N(0,1)) driven by a fast xorshift64 PRNG.
     *
     * 2. **Spatial coherence** — real grains are 2–5 µm crystals that clump; at typical
     *    scan resolution they appear as 2–3 px blobs, not isolated point samples.
     *    A separable box-blur over the generated field creates this structure.
     *    Blob radius scales with strength (fine grain at low values, coarse at high).
     *
     * 3. **Luminance masking** — grain is most visible in midtones (~lum = 0.4–0.6),
     *    suppressed at both extremes:
     *    • Deep shadows: low photon count, grain below noise floor → ~10% grain present
     *    • Bright highlights: dye-layer saturation bleaches grain → fast rolloff
     *
     * 4. **Per-channel independence** — color film has separate R, G, B emulsion layers
     *    with slightly different crystal sizes and sensitivities.  A secondary independent
     *    grain field drives a small chroma perturbation (R and B shift in opposition,
     *    G remains as the luma reference), producing the subtle cyan/magenta flicker
     *    characteristic of pushed color film (Portra 800, Ektar 100 at box speed, etc.).
     *
     * Seed is fixed per (width, height) so the preview is stable across slider changes.
     */
    private fun applyFilmGrain(src: Bitmap, strength: Float): Bitmap {
        val w = src.width
        val h = src.height
        val n = w * h

        val intArrays = intArrayPool.getIntArrays(n)
        val floatArrays = floatArrayPool.getFloatArrays(n)
        val pixels = intArrays[0]
        val lumaG = floatArrays[0]
        val chromaG = floatArrays[1]
        val tempL = floatArrays[2]
        val tempC = floatArrays[3]

        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val amp = strength / 100f
        val grainAmp = amp * 16f
        val blurR = if (amp < 0.5f) 1 else 2
        val blurD = (2 * blurR + 1).toFloat()

        var xs1 = (w.toLong() * 1664525L xor h.toLong() * 22695477L xor 0x3141592653589793L) or 1L
        var xs2 = (xs1 * 6364136223846793005L + 1442695040888963407L) or 1L

        fun rng1(): Long { xs1 = xs1 xor (xs1 shl 13); xs1 = xs1 xor (xs1 ushr 7); xs1 = xs1 xor (xs1 shl 17); return xs1 }
        fun rng2(): Long { xs2 = xs2 xor (xs2 shl 7); xs2 = xs2 xor (xs2 ushr 9); xs2 = xs2 xor (xs2 shl 8); return xs2 }
        fun f1() = (rng1() and 0xFFFFFFL).toFloat() / 0x800000.toFloat() - 1f
        fun f2() = (rng2() and 0xFFFFFFL).toFloat() / 0x800000.toFloat() - 1f
        fun gauss1() = (f1() + f1() + f1()) * 0.5774f
        fun gauss2() = (f2() + f2() + f2()) * 0.5774f

        for (i in 0 until n) {
            lumaG[i] = gauss1()
            chromaG[i] = gauss2() * 0.35f
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val x0 = maxOf(0, x - blurR); val x1 = minOf(w - 1, x + blurR)
                val cnt = (x1 - x0 + 1).toFloat()
                var sL = 0f; var sC = 0f
                for (xi in x0..x1) { val idx = y * w + xi; sL += lumaG[idx]; sC += chromaG[idx] }
                tempL[y * w + x] = sL / cnt; tempC[y * w + x] = sC / cnt
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val y0 = maxOf(0, y - blurR); val y1 = minOf(h - 1, y + blurR)
                val cnt = (y1 - y0 + 1).toFloat()
                var sL = 0f; var sC = 0f
                for (yi in y0..y1) { val idx = yi * w + x; sL += tempL[idx]; sC += tempC[idx] }
                lumaG[y * w + x] = sL / cnt; chromaG[y * w + x] = sC / cnt
            }
        }

        for (i in 0 until n) {
            val px = pixels[i]
            val a  = (px ushr 24) and 0xFF
            val ri = (px shr 16) and 0xFF
            val gi = (px shr  8) and 0xFF
            val bi =  px          and 0xFF

            val lum = (0.2126f * ri + 0.7152f * gi + 0.0722f * bi) / 255f

            val l = lum.coerceIn(0f, 1f)
            val lumMask = if (l < 0.5f) {
                val t = l * 2f;         0.1f + t * (2f - t) * 0.9f
            } else {
                val t = (l - 0.5f) * 2f; 0.1f + (1f - t * t) * 0.9f
            }

            val gL = lumaG[i]   * blurD * blurD * grainAmp * lumMask
            val gC = chromaG[i] * blurD * blurD * grainAmp * lumMask

            val dR = (gL + gC * 0.7f).toInt()
            val dG =  gL.toInt()
            val dB = (gL - gC * 0.7f).toInt()

            pixels[i] = (a shl 24) or
                ((ri + dR).coerceIn(0, 255) shl 16) or
                ((gi + dG).coerceIn(0, 255) shl  8) or
                (bi + dB).coerceIn(0, 255)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
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

/**
 * Transforms a [NormalizedRect] from rotated image coordinates back to original
 * image coordinates. cropRect is in 0-1 normalized coords for the ORIGINAL image
 * (W×H); after 90°/270° rotation dimensions swap (H×W), so we must remap the rect.
 *
 * The crop is applied BEFORE rotation in the pipeline, so we need the INVERSE
 * transform: given a desired crop in the ROTATED image, find what crop on the
 * ORIGINAL would produce it after rotation.
 *
 * NormalizedRect uses (left, top, right, bottom) — NOT width/height.
 *
 * Inverse 90° CW (original W×H → rotated H×W):
 *   Rotated pixel (rx, ry) comes from original (H-1-ry, rx)
 *   So original left = (H-1)/W - new_top - new_height
 *   Original top = new_left * W / H
 *   Original width = new_width * H / W
 *   Original height = new_height * W / H
 *
 * Inverse 180°: same dimensions, flip both axes.
 *   Original left = 1 - new_left - new_width
 *   Original top = 1 - new_top - new_height
 *
 * Inverse 270° CW (original W×H → rotated H×W):
 *   Rotated pixel (rx, ry) comes from original (ry, W-1-rx)
 *   So original left = new_top * H / W
 *   Original top = 1 - new_left - new_width
 *   Original width = new_height * H / W
 *   Original height = new_width * W / H
 */
private fun transformCropRectForRotation(cropRect: com.photonlab.domain.model.NormalizedRect, rotation: Int): com.photonlab.domain.model.NormalizedRect {
    // cropRect is in 0-1 for original image dims (srcWidth, srcHeight).
    // We need to transform from ROTATED coords back to ORIGINAL coords.
    // The caller passes rotation in degrees (90, 180, 270).
    //
    // For the inverse transform, we treat cropRect as the DESIRED crop in
    // the rotated image and compute what crop in the original (before rotation)
    // would produce that result.

    val newLeft = cropRect.left
    val newTop = cropRect.top
    val newRight = cropRect.right
    val newBottom = cropRect.bottom
    val newWidth = newRight - newLeft
    val newHeight = newBottom - newTop

    // Aspect ratio of original (W/H). For 90°/270° rotations where dimensions
    // swap, we need this to properly normalize the swapped dimensions.
    // We derive it from the normalized coords - if original was square, ratio=1.
    // The actual W and H don't matter for the normalized calculation since
    // we just need the ratio to convert between the swapped dimensions.
    // For non-square images the ratio H/W converts rotated-dim fractions to original-dim fractions.

    return when (rotation) {
90 -> {
    // Inverse transform: desired crop on rotated (H×W) → original crop on original (W×H)
    // x' = y, y' = W-1-x (forward), so x = y', y = W-1-y' (inverse)
    // Original left edge = newTop (since x' = y, left edge maps directly)
    // Original top edge = W-1 - newRight (since y' = W-1-x, right edge maps to top)
    val origLeft = newTop
    val origTop = 1f - newRight
    val origWidth = newHeight
    val origHeight = newWidth
    com.photonlab.domain.model.NormalizedRect(
        left = origLeft,
        top = origTop,
        right = origLeft + origWidth,
        bottom = origTop + origHeight
    )
}
180 -> {
                // Same dimensions, flip both axes
                val origLeft = 1f - newRight
                val origTop = 1f - newBottom
                com.photonlab.domain.model.NormalizedRect(
                    left = origLeft,
                    top = origTop,
                    right = origLeft + newWidth,
                    bottom = origTop + newHeight
                )
            }
270 -> {
    // Original W×H → Rotated H×W
    // Forward 270° CW: x' = H-1-y, y' = x
    // Inverse (to find original coords from rotated crop):
    //   x = y', y = H-1-x'  →  origLeft = newTop, origTop = 1 - newLeft
    val origLeft = 1f - newBottom
    val origTop = newLeft
    val origWidth = newHeight
    val origHeight = newWidth
    com.photonlab.domain.model.NormalizedRect(
        left = origLeft,
        top = origTop,
        right = origLeft + origWidth,
        bottom = origTop + origHeight
    )
}
    else -> cropRect
    }
}

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
