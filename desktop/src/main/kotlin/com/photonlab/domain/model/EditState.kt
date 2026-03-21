package com.photonlab.domain.model

/**
 * Immutable snapshot of all current edit parameters.
 * All slider values are in their "raw" UI range; the rendering pipeline
 * normalises them before passing to the shader.
 *
 * Desktop version — no Android dependencies. [lutPath] replaces Android's Uri.
 */
data class EditState(
    // Tone
    val exposure: Float = 0f,     // -5 .. +5 EV
    val luminosity: Float = 0f,   // -100 .. +100
    val contrast: Float = 0f,     // -100 .. +100
    val highlights: Float = 0f,   // -100 .. +100
    val shadows: Float = 0f,      // -100 .. +100
    // Color
    val saturation: Float = 0f,   // -100 .. +100 (negative normalized by /100, positive by /200)
    val vibrance: Float = 0f,     // -100 .. +100
    val temperature: Float = 0f,  // -100 .. +100 (cool → warm)
    val tint: Float = 0f,         // -100 .. +100 (green → magenta)
    // LUT — stored as canonical path string (empty = no LUT)
    val lutPath: String = "",
    // Transform
    val rotation: Int = 0,           // 0, 90, 180, 270 degrees (90° steps)
    val fineRotation: Float = 0f,    // -45 .. +45 degrees (free rotation, 0.1° steps)
    val cropRect: NormalizedRect? = null, // null = no crop
    // Frame
    val frameEnabled: Boolean = false,
    val frameColor: Int = 0xFFFFFFFF.toInt(), // ARGB; default white
    val frameRatio: Float = 0f,               // 0 = same ratio as image; other = target W/H ratio
    val frameSizePct: Float = 3f,             // border width as % of shorter output dimension (0..30)
    // Detail
    val sharpening: Float = 0f,              // 0 .. 100
    val noise: Float = 0f,                   // -100 (denoise) .. +100 (grain)
    // Date imprint
    val dateImprint: DateImprintSettings = DateImprintSettings(),
) {
    val isDefault: Boolean
        get() = exposure == 0f && luminosity == 0f && contrast == 0f &&
                highlights == 0f && shadows == 0f && saturation == 0f &&
                vibrance == 0f && temperature == 0f && tint == 0f &&
                lutPath.isEmpty() && rotation == 0 && fineRotation == 0f &&
                cropRect == null && !frameEnabled && sharpening == 0f && noise == 0f &&
                !dateImprint.enabled
}
