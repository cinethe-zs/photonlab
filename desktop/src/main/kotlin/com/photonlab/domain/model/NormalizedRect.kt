package com.photonlab.domain.model

/**
 * Crop rectangle in normalised image coordinates (0.0 .. 1.0).
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width get() = right - left
    val height get() = bottom - top

    companion object {
        val FULL = NormalizedRect(0f, 0f, 1f, 1f)
    }
}
