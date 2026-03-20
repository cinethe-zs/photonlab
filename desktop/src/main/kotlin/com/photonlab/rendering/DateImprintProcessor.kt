package com.photonlab.rendering

import com.photonlab.domain.model.DateImprintFont
import com.photonlab.domain.model.DateImprintPosition
import com.photonlab.domain.model.DateImprintSettings
import com.photonlab.platform.DesktopBitmap
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Date
import kotlin.math.ceil
import kotlin.math.exp

/**
 * Burns a date stamp onto a [DesktopBitmap] using Java2D, mirroring the PhotonCam
 * DateImprintProcessor behaviour (4 layers: ghost, shadow, glow halo, sharp text).
 *
 * For the LED font, characters that DSEG7 cannot render faithfully (apostrophe, hyphen)
 * are drawn with a monospaced fallback font via [AttributedString], so the rest of the
 * digits still use the 7-segment typeface.
 */
object DateImprintProcessor {

    // DSEG14 Classic — 14-segment display font, supports ' and - natively.
    private val ledFont: Font by lazy {
        runCatching {
            val stream = DateImprintProcessor::class.java.getResourceAsStream("/fonts/dseg14.ttf")
                ?: error("dseg14.ttf not found in classpath")
            Font.createFont(Font.TRUETYPE_FONT, stream)
        }.getOrDefault(Font(Font.MONOSPACED, Font.PLAIN, 12))
    }

    fun burn(src: DesktopBitmap, settings: DateImprintSettings, date: Date): DesktopBitmap {
        if (!settings.enabled) return src

        // Copy source into a new mutable bitmap
        val result = DesktopBitmap.create(src.width, src.height)
        val initG = result.image.createGraphics()
        initG.drawImage(src.image, 0, 0, null)
        initG.dispose()

        val w = result.width.toFloat()
        val h = result.height.toFloat()
        val dateString = settings.style.format(date)

        // ── Size ─────────────────────────────────────────────────────────────
        val textSize = (w * settings.sizePercent / 100f).coerceIn(6f, 96f)

        // ── Typeface ──────────────────────────────────────────────────────────
        val baseFont: Font = when (settings.font) {
            DateImprintFont.LED       -> ledFont.deriveFont(textSize)
            DateImprintFont.MONOSPACE -> Font(Font.MONOSPACED, Font.PLAIN, textSize.toInt())
            DateImprintFont.BOLD      -> Font(Font.SANS_SERIF, Font.BOLD, textSize.toInt())
            DateImprintFont.SERIF     -> Font(Font.SERIF, Font.PLAIN, textSize.toInt())
            DateImprintFont.CONDENSED -> Font(Font.SANS_SERIF, Font.PLAIN, textSize.toInt())
        }

        // ── Color / opacity ────────────────────────────────────────────────
        val (cr, cg, cb) = parseHex(settings.color.hex)
        val opacityFactor  = settings.opacity.coerceIn(0, 100) / 100f
        val glowRadius     = textSize * (settings.glowAmount.coerceIn(0, 100) / 100f) * 0.375f
        val textBlurRadius = textSize * (settings.blurAmount.coerceIn(0, 100) / 100f) * 0.2f

        // ── Measure text ──────────────────────────────────────────────────
        val fm = measureFontMetrics(baseFont)
        val textWidth  = fm.stringWidth(dateString)
        val textAscent = fm.ascent
        val textHeight = fm.height

        // ── Draw function (used in both direct and blurred layers) ─────────
        val drawText: (Graphics2D, Float, Float) -> Unit = { g2, lx, ly ->
            g2.font = baseFont
            g2.drawString(dateString, lx, ly)
        }

        // ── Position ─────────────────────────────────────────────────────
        val margin = w * 0.10f
        val x = when (settings.position) {
            DateImprintPosition.BOTTOM_RIGHT, DateImprintPosition.TOP_RIGHT -> w - textWidth - margin
            DateImprintPosition.BOTTOM_LEFT,  DateImprintPosition.TOP_LEFT  -> margin
            DateImprintPosition.BOTTOM_CENTER                               -> (w - textWidth) / 2f
        }
        val y = when (settings.position) {
            DateImprintPosition.BOTTOM_RIGHT,
            DateImprintPosition.BOTTOM_LEFT,
            DateImprintPosition.BOTTOM_CENTER -> h - h * 0.075f
            DateImprintPosition.TOP_RIGHT,
            DateImprintPosition.TOP_LEFT      -> h * 0.085f + textSize
        }

        // ── Draw non-blurred layers directly ──────────────────────────────
        val g2 = result.image.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // Layer 1: LED inactive-segment ghost (LED font only)
        if (settings.font == DateImprintFont.LED) {
            g2.color = java.awt.Color(cr, cg, cb, (30 * opacityFactor).toInt().coerceIn(0, 255))
            drawText(g2, x, y)
        }

        // Layer 2: Drop shadow
        g2.color = java.awt.Color(0, 0, 0, (160 * opacityFactor).toInt().coerceIn(0, 255))
        drawText(g2, x + 2f, y + 2f)
        g2.dispose()

        // Layer 3: Glow halo (blurred outward from text)
        if (glowRadius > 0f) {
            compositeBlurredText(
                dst = result.image,
                textW = textWidth, ascent = textAscent, textH = textHeight,
                r = cr, g = cg, b = cb, alpha = (255 * opacityFactor).toInt().coerceIn(0, 255),
                x = x, y = y, blurRadius = glowRadius, repeat = 1,
                drawFn = drawText,
            )
        }

        // Layer 4: Sharp/blur text drawn blurRepeat times
        compositeBlurredText(
            dst = result.image,
            textW = textWidth, ascent = textAscent, textH = textHeight,
            r = cr, g = cg, b = cb, alpha = (255 * opacityFactor).toInt().coerceIn(0, 255),
            x = x, y = y, blurRadius = textBlurRadius, repeat = settings.blurRepeat.coerceIn(1, 20),
            drawFn = drawText,
        )

        return result
    }

    /**
     * Renders text to a tight bounding-box scratch image via [drawFn], optionally
     * Gaussian-blurs it, then composites it [repeat] times onto [dst] at ([x], [y]).
     */
    private fun compositeBlurredText(
        dst: BufferedImage,
        textW: Int, ascent: Int, textH: Int,
        r: Int, g: Int, b: Int, alpha: Int,
        x: Float, y: Float,
        blurRadius: Float, repeat: Int,
        drawFn: (Graphics2D, Float, Float) -> Unit,
    ) {
        val padding = (blurRadius * 3).toInt().coerceAtLeast(4)

        val boxX = (x.toInt() - padding).coerceAtLeast(0)
        val boxY = (y.toInt() - ascent - padding).coerceAtLeast(0)
        val rawBoxW = textW + padding * 2 + (x.toInt() - boxX)
        val rawBoxH = textH + padding * 2
        val boxW = rawBoxW.coerceAtMost(dst.width  - boxX).coerceAtLeast(1)
        val boxH = rawBoxH.coerceAtMost(dst.height - boxY).coerceAtLeast(1)

        val tmp = BufferedImage(boxW, boxH, BufferedImage.TYPE_INT_ARGB)
        val tg = tmp.createGraphics()
        tg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON)
        tg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        tg.color = java.awt.Color(r, g, b, alpha)
        drawFn(tg, x - boxX, y - boxY)
        tg.dispose()

        val blurred = if (blurRadius > 0.5f) gaussianBlurARGB(tmp, blurRadius) else tmp

        val dg = dst.createGraphics()
        repeat(repeat) { dg.drawImage(blurred, boxX, boxY, null) }
        dg.dispose()
    }

    /**
     * Separable Gaussian blur that correctly pre-multiplies alpha before blending
     * and divides back after, avoiding colour fringing at transparent edges.
     */
    private fun gaussianBlurARGB(src: BufferedImage, radius: Float): BufferedImage {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getRGB(0, 0, w, h, pixels, 0, w)

        val kr = ceil(radius * 2.0).toInt().coerceAtLeast(1)
        val ks = kr * 2 + 1
        val sigma = (radius / 2f).coerceAtLeast(0.1f)
        val kernel = FloatArray(ks) { i ->
            val d = (i - kr).toFloat()
            exp(-d * d / (2f * sigma * sigma))
        }
        val ksum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= ksum

        val fa = FloatArray(w * h); val fr = FloatArray(w * h)
        val fg = FloatArray(w * h); val fb = FloatArray(w * h)
        for (i in pixels.indices) {
            val a = ((pixels[i] ushr 24) and 0xFF) / 255f
            fa[i] = a
            fr[i] = ((pixels[i] ushr 16) and 0xFF) / 255f * a
            fg[i] = ((pixels[i] ushr  8) and 0xFF) / 255f * a
            fb[i] = ( pixels[i]          and 0xFF) / 255f * a
        }

        val ta = FloatArray(w * h); val tr = FloatArray(w * h)
        val tg = FloatArray(w * h); val tb = FloatArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sa = 0f; var sr = 0f; var sg = 0f; var sb = 0f
                for (ki in 0 until ks) {
                    val xi = (x + ki - kr).coerceIn(0, w - 1)
                    val k  = kernel[ki]; val idx = y * w + xi
                    sa += fa[idx] * k; sr += fr[idx] * k; sg += fg[idx] * k; sb += fb[idx] * k
                }
                val i = y * w + x
                ta[i] = sa; tr[i] = sr; tg[i] = sg; tb[i] = sb
            }
        }

        // Vertical pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sa = 0f; var sr = 0f; var sg = 0f; var sb = 0f
                for (ki in 0 until ks) {
                    val yi = (y + ki - kr).coerceIn(0, h - 1)
                    val k  = kernel[ki]; val idx = yi * w + x
                    sa += ta[idx] * k; sr += tr[idx] * k; sg += tg[idx] * k; sb += tb[idx] * k
                }
                val i = y * w + x
                val a = sa.coerceIn(0f, 1f)
                val div = if (a > 1e-4f) a else 1f
                pixels[i] = ((a * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or
                             (((sr / div) * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                             (((sg / div) * 255f + 0.5f).toInt().coerceIn(0, 255) shl  8) or
                             (((sb / div) * 255f + 0.5f).toInt().coerceIn(0, 255))
            }
        }

        val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        dst.setRGB(0, 0, w, h, pixels, 0, w)
        return dst
    }

    /** Create a 1×1 scratch image to obtain FontMetrics without a display. */
    private fun measureFontMetrics(font: Font): java.awt.FontMetrics {
        val tmp = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g   = tmp.createGraphics()
        g.font  = font
        val fm  = g.fontMetrics
        g.dispose()
        return fm
    }

    private fun parseHex(hex: String): Triple<Int, Int, Int> {
        val h = hex.trimStart('#')
        return Triple(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
    }
}
