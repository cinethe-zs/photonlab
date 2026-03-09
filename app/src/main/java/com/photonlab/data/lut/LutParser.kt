package com.photonlab.data.lut

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.photonlab.domain.model.LutFile
import com.photonlab.domain.model.LutType
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.roundToInt

object LutParser {

    /** Parse a LUT file from a URI. Detects format from extension or content. */
    fun parse(context: Context, uri: Uri, displayName: String): LutFile {
        val lower = displayName.lowercase()
        return when {
            lower.endsWith(".cube") -> parseCube(context, uri, displayName)
            lower.endsWith(".png") || lower.endsWith(".tiff") || lower.endsWith(".tif") ->
                parseHaldClut(context, uri, displayName)
            else -> throw IllegalArgumentException("Unsupported LUT format: $displayName")
        }
    }

    // ── .cube (3DLUT) ──────────────────────────────────────────────────────────

    private fun parseCube(context: Context, uri: Uri, name: String): LutFile {
        val reader = BufferedReader(
            InputStreamReader(context.contentResolver.openInputStream(uri)!!)
        )
        var size = 0
        val entries = mutableListOf<Float>()

        reader.use { br ->
            br.lineSequence().forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("#") || line.isEmpty() -> Unit
                    line.startsWith("LUT_3D_SIZE") -> size = line.split("\\s+".toRegex())[1].toInt()
                    line.startsWith("DOMAIN_") -> Unit // ignore domain clamp lines for now
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
            uri = uri,
            name = name,
            type = LutType.CUBE_3D,
            data = entries.toFloatArray(),
            size = size,
        )
    }

    // ── HaldCLUT PNG ───────────────────────────────────────────────────────────

    private fun parseHaldClut(context: Context, uri: Uri, name: String): LutFile {
        val bitmap = context.contentResolver.openInputStream(uri)!!.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: throw IllegalArgumentException("Cannot decode HaldCLUT image: $name")

        // HaldCLUT: image is (L³) × (L³) pixels for level L (e.g. L=8 → 512×512, L=12 → 1728×1728).
        // Total pixels = L^6, so L = totalPixels^(1/6) = cbrt(sqrt(totalPixels)).
        // Cube edge (entries per axis) = L^2.
        val totalPixels = bitmap.width * bitmap.height
        val level = Math.cbrt(Math.sqrt(totalPixels.toDouble())).roundToInt()
        val size = level * level  // cube edge length = L²

        require(size * size * size == totalPixels) {
            "HaldCLUT image dimensions don't correspond to a valid level (${bitmap.width}×${bitmap.height})"
        }

        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()

        val data = FloatArray(totalPixels * 3)
        pixels.forEachIndexed { i, px ->
            data[i * 3 + 0] = ((px shr 16) and 0xFF) / 255f
            data[i * 3 + 1] = ((px shr 8) and 0xFF) / 255f
            data[i * 3 + 2] = (px and 0xFF) / 255f
        }

        return LutFile(
            uri = uri,
            name = name,
            type = LutType.HALD_CLUT,
            data = data,
            size = size,
        )
    }
}
