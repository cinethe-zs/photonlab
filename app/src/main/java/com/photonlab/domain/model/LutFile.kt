package com.photonlab.domain.model

import android.net.Uri

enum class LutType { CUBE_3D, HALD_CLUT }

data class LutFile(
    val uri: Uri,
    val name: String,
    val type: LutType,
    /** Parsed LUT data: flat R,G,B float array, length = size³ × 3 */
    val data: FloatArray,
    /** Edge length of the 3D cube (e.g. 33 for a 33³ LUT) */
    val size: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LutFile) return false
        return uri == other.uri && name == other.name && type == other.type && size == other.size
    }

    override fun hashCode(): Int = uri.hashCode()
}
