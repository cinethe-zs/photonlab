package com.photonlab.data.lut

import com.photonlab.domain.model.LutFile
import com.photonlab.domain.model.LutType
import com.photonlab.platform.DesktopBitmap
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import javax.imageio.ImageIO
import kotlin.math.roundToInt

object LutParser {

    /** Parse a LUT file from a [File]. Detects format from extension. */
    fun parse(file: File): LutFile {
        val lower = file.name.lowercase()
        return when {
            lower.endsWith(".cube") -> parseCube(file)
            lower.endsWith(".png") || lower.endsWith(".tiff") || lower.endsWith(".tif") ->
                parseHaldClut(file)
            else -> throw IllegalArgumentException("Unsupported LUT format: ${file.name}")
        }
    }

    // ── .cube (3DLUT) ──────────────────────────────────────────────────────

    private fun parseCube(file: File): LutFile {
        val reader = BufferedReader(InputStreamReader(FileInputStream(file)))
        var size = 0
        val entries = mutableListOf<Float>()

        reader.use { br ->
            br.lineSequence().forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("#") || line.isEmpty() -> Unit
                    line.startsWith("LUT_3D_SIZE") -> size = line.split("\\s+".toRegex())[1].toInt()
                    line.startsWith("DOMAIN_") -> Unit
                    line.startsWith("TITLE") -> Unit
                    else -> {
                        val parts = line.split("\\s+".toRegex())
                        if (parts.size == 3) {
                            entries.add(parts[0].toFloat())
                            entries.add(parts[1].toFloat())
                            entries.add(parts[2].toFloat())
                        }
                    }
                }
            }
        }

        require(size > 0) { "LUT_3D_SIZE not found in .cube file" }
        require(entries.size == size * size * size * 3) {
            "Expected ${size * size * size * 3} values, got ${entries.size}"
        }

        return LutFile(
            file = file,
            name = file.name,
            type = LutType.CUBE_3D,
            data = entries.toFloatArray(),
            size = size,
        )
    }

    // ── HaldCLUT PNG ───────────────────────────────────────────────────────

    private fun parseHaldClut(file: File): LutFile {
        val img = ImageIO.read(file) ?: throw IllegalArgumentException("Cannot decode HaldCLUT image: ${file.name}")
        val bitmap = DesktopBitmap.fromBufferedImage(img)

        val totalPixels = bitmap.width * bitmap.height
        val level = Math.cbrt(Math.sqrt(totalPixels.toDouble())).roundToInt()
        val size = level * level

        require(size * size * size == totalPixels) {
            "HaldCLUT image dimensions don't correspond to a valid level (${bitmap.width}×${bitmap.height})"
        }

        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val data = FloatArray(totalPixels * 3)
        pixels.forEachIndexed { i, px ->
            data[i * 3 + 0] = ((px shr 16) and 0xFF) / 255f
            data[i * 3 + 1] = ((px shr 8) and 0xFF) / 255f
            data[i * 3 + 2] = (px and 0xFF) / 255f
        }

        return LutFile(
            file = file,
            name = file.name,
            type = LutType.HALD_CLUT,
            data = data,
            size = size,
        )
    }
}
