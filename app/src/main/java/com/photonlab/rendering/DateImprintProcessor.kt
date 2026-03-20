package com.photonlab.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.photonlab.domain.model.DateImprintFont
import com.photonlab.domain.model.DateImprintPosition
import com.photonlab.domain.model.DateImprintSettings
import java.util.Date

/**
 * Burns a date stamp onto a [Bitmap] using Android Canvas/Paint, mirroring the desktop
 * DateImprintProcessor behaviour (4 layers: ghost, shadow, glow halo, sharp/blurred text).
 *
 * The DSEG14 Classic font is loaded from assets/fonts/dseg14.ttf and cached after first load.
 */
object DateImprintProcessor {

    @Volatile private var ledTypeface: Typeface? = null

    private fun getLedTypeface(context: Context): Typeface =
        ledTypeface ?: synchronized(this) {
            ledTypeface ?: runCatching {
                Typeface.createFromAsset(context.assets, "fonts/dseg14.ttf")
            }.getOrDefault(Typeface.MONOSPACE).also { ledTypeface = it }
        }

    fun burn(src: Bitmap, settings: DateImprintSettings, date: Date, context: Context): Bitmap {
        if (!settings.enabled) return src

        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val w = result.width.toFloat()
        val h = result.height.toFloat()
        val dateString = settings.style.format(date)

        val textSize = (w * settings.sizePercent / 100f).coerceIn(6f, 96f)

        val typeface: Typeface = when (settings.font) {
            DateImprintFont.LED       -> getLedTypeface(context)
            DateImprintFont.MONOSPACE -> Typeface.MONOSPACE
            DateImprintFont.BOLD      -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            DateImprintFont.SERIF     -> Typeface.SERIF
            DateImprintFont.CONDENSED -> Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val (cr, cg, cb) = parseHex(settings.color.hex)
        val opacityFactor = settings.opacity.coerceIn(0, 100) / 100f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            this.typeface = typeface
        }

        val textWidth = paint.measureText(dateString)

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

        // Layer 1: LED inactive-segment ghost (LED font only)
        if (settings.font == DateImprintFont.LED) {
            paint.color = Color.argb((30 * opacityFactor).toInt().coerceIn(0, 255), cr, cg, cb)
            paint.maskFilter = null
            canvas.drawText(dateString, x, y, paint)
        }

        // Layer 2: Drop shadow
        paint.color = Color.argb((160 * opacityFactor).toInt().coerceIn(0, 255), 0, 0, 0)
        paint.maskFilter = null
        canvas.drawText(dateString, x + 2f, y + 2f, paint)

        val mainAlpha = (255 * opacityFactor).toInt().coerceIn(0, 255)
        paint.color = Color.argb(mainAlpha, cr, cg, cb)

        // Layer 3: Glow halo (blurred outward from text)
        val glowRadius = textSize * (settings.glowAmount.coerceIn(0, 100) / 100f) * 0.375f
        if (glowRadius > 0f) {
            paint.maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
            canvas.drawText(dateString, x, y, paint)
        }

        // Layer 4: Sharp/blurred text drawn blurRepeat times
        val blurRadius = textSize * (settings.blurAmount.coerceIn(0, 100) / 100f) * 0.2f
        paint.maskFilter = if (blurRadius > 0.5f)
            BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL) else null
        repeat(settings.blurRepeat.coerceIn(1, 20)) {
            canvas.drawText(dateString, x, y, paint)
        }
        paint.maskFilter = null

        return result
    }

    private fun parseHex(hex: String): Triple<Int, Int, Int> {
        val h = hex.trimStart('#')
        return Triple(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
    }
}
