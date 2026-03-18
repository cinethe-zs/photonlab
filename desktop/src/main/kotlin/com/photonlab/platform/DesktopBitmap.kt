package com.photonlab.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Desktop equivalent of Android's android.graphics.Bitmap.
 *
 * All pixels are stored as packed ARGB ints (same layout as Android ARGB_8888 and AWT TYPE_INT_ARGB):
 *   bits 31-24: alpha,  23-16: red,  15-8: green,  7-0: blue
 */
class DesktopBitmap(val image: BufferedImage) {

    val width: Int  get() = image.width
    val height: Int get() = image.height

    /**
     * Read pixels into [pixels] array. Compatible with Android's Bitmap.getPixels().
     * [offset] = start index in [pixels]; [stride] = row width in [pixels].
     */
    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        image.getRGB(x, y, w, h, pixels, offset, stride)
    }

    /**
     * Write pixels from [pixels] array. Compatible with Android's Bitmap.setPixels().
     */
    fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        image.setRGB(x, y, w, h, pixels, offset, stride)
    }

    /** Convert to Compose ImageBitmap for display in the UI. */
    fun asImageBitmap(): ImageBitmap = image.toComposeImageBitmap()

    fun recycle() { /* no-op on desktop */ }

    companion object {

        /** Create a blank ARGB bitmap of given dimensions. */
        fun create(width: Int, height: Int): DesktopBitmap =
            DesktopBitmap(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB))

        /** Create a filled solid-colour bitmap. [argb] is packed ARGB. */
        fun createFilled(width: Int, height: Int, argb: Int): DesktopBitmap {
            val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g2d = img.createGraphics()
            g2d.color = java.awt.Color(argb, true)
            g2d.fillRect(0, 0, width, height)
            g2d.dispose()
            return DesktopBitmap(img)
        }

        /** Scale to target dimensions. [filter] = bilinear when true. */
        fun createScaled(src: DesktopBitmap, w: Int, h: Int, filter: Boolean): DesktopBitmap {
            val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g2d = scaled.createGraphics()
            g2d.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                if (filter) RenderingHints.VALUE_INTERPOLATION_BILINEAR
                else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
            )
            g2d.drawImage(src.image, 0, 0, w, h, null)
            g2d.dispose()
            return DesktopBitmap(scaled)
        }

        /** Extract a sub-region. */
        fun createSubset(src: DesktopBitmap, x: Int, y: Int, w: Int, h: Int): DesktopBitmap {
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g2d = out.createGraphics()
            g2d.drawImage(src.image, 0, 0, w, h, x, y, x + w, y + h, null)
            g2d.dispose()
            return DesktopBitmap(out)
        }

        /**
         * Rotate by exact 90° multiples. Output dimensions are swapped for 90/270.
         * [degrees] must be 0, 90, 180, or 270.
         */
        fun createRotated90(src: DesktopBitmap, degrees: Int): DesktopBitmap {
            if (degrees == 0) return src
            val outW = if (degrees % 180 == 0) src.width else src.height
            val outH = if (degrees % 180 == 0) src.height else src.width
            val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB)
            val g2d = out.createGraphics()
            val tx = AffineTransform()
            when ((degrees + 360) % 360) {
                90  -> { tx.translate(outW.toDouble(), 0.0); tx.rotate(Math.PI / 2) }
                180 -> { tx.translate(outW.toDouble(), outH.toDouble()); tx.rotate(Math.PI) }
                270 -> { tx.translate(0.0, outH.toDouble()); tx.rotate(-Math.PI / 2) }
            }
            g2d.drawImage(src.image, tx, null)
            g2d.dispose()
            return DesktopBitmap(out)
        }

        /**
         * Free rotation by [degrees] with zoom-to-fill so no black bars appear.
         * Output dimensions are the same as input.
         */
        fun createFineRotated(src: DesktopBitmap, degrees: Float): DesktopBitmap {
            if (degrees == 0f) return src
            val w = src.width.toDouble()
            val h = src.height.toDouble()
            val rad = Math.toRadians(degrees.toDouble())
            val c = abs(cos(rad))
            val s = abs(sin(rad))
            val scale = max((w * c + h * s) / w, (w * s + h * c) / h)
            val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
            val g2d = out.createGraphics()
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val tx = AffineTransform()
            tx.translate(w / 2, h / 2)
            tx.scale(scale, scale)
            tx.rotate(Math.toRadians(degrees.toDouble()))
            tx.translate(-w / 2, -h / 2)
            g2d.drawImage(src.image, tx, null)
            g2d.dispose()
            return DesktopBitmap(out)
        }

        /** Ensure the image is stored as TYPE_INT_ARGB (converts if necessary). */
        fun fromBufferedImage(img: BufferedImage): DesktopBitmap {
            if (img.type == BufferedImage.TYPE_INT_ARGB) return DesktopBitmap(img)
            val converted = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
            val g2d = converted.createGraphics()
            g2d.drawImage(img, 0, 0, null)
            g2d.dispose()
            return DesktopBitmap(converted)
        }
    }
}
