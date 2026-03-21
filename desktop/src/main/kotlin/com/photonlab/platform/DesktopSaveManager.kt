package com.photonlab.platform

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Saves processed bitmaps to ~/Pictures/PhotonLab/ as JPEG files.
 */
object DesktopSaveManager {

    private val outputDir: File
        get() = File(System.getProperty("user.home"), "Pictures/PhotonLab").also { it.mkdirs() }

    /**
     * Save [bitmap] as a JPEG file with the given quality (1–100).
     * Returns the saved file path.
     */
    fun saveJpeg(bitmap: DesktopBitmap, quality: Int): File {
        val filename = "photonlab_${System.currentTimeMillis()}.jpg"
        val file = File(outputDir, filename)

        // Convert to RGB (JPEG doesn't support alpha)
        val rgbImage = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_RGB)
        val g2d = rgbImage.createGraphics()
        g2d.drawImage(bitmap.image, 0, 0, null)
        g2d.dispose()

        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val writeParam: ImageWriteParam = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality.coerceIn(1, 100) / 100f
        }
        ImageIO.createImageOutputStream(file).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(rgbImage, null, null), writeParam)
            writer.dispose()
        }
        rgbImage.flush()
        return file
    }
}
