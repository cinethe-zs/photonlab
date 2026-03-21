package com.photonlab.domain.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DateImprintStyle(val label: String) {
    // ── Classic film camera styles ────────────────────────────────────────
    CLASSIC    ("DEC 24 '95"),   // original disposable-camera look
    YEAR_FIRST ("'95 DEC 24"),   // year-first variant
    WITH_TIME  ("24 DEC 14:30"), // date + time, no year

    // ── Numeric YY (2-digit year) ─────────────────────────────────────────
    NUMERIC_EU ("24.12.95"),     // European dot separator
    NUMERIC_US ("12/24/95"),     // US slash separator
    DD_MM_YY   ("24 12 95"),     // space-separated EU
    MM_DD_YY   ("12 24 95"),     // space-separated US
    YY_MM_DD   ("'26 03 13"),    // ISO-ish with apostrophe year

    // ── Numeric YYYY (4-digit year) ───────────────────────────────────────
    YYYY_MM_DD ("2026 03 13"),   // space-separated, year first
    DD_MM_YYYY ("24.12.2026"),   // EU full year
    MM_DD_YYYY ("12/24/2026"),   // US full year
    ISO_8601   ("2026-12-24"),   // ISO 8601 dashes
    DD_MMM_YYYY("24 DEC 2026"),  // day month(text) year
    MMM_YYYY   ("DEC 2026"),     // month + year only

    // ── Date + time ───────────────────────────────────────────────────────
    DATETIME_EU("24.12.95 14:30"),    // EU date + time
    DATETIME_US("12/24/95 14:30"),    // US date + time
    DATETIME_ISO("2026-12-24 14:30"), // ISO date + time
    TIME_ONLY  ("14:30");             // time only (no date)

    fun format(date: Date): String = when (this) {
        CLASSIC     -> SimpleDateFormat("MMM dd ''yy",       Locale.US).format(date).uppercase()
        YEAR_FIRST  -> SimpleDateFormat("''yy MMM dd",       Locale.US).format(date).uppercase()
        WITH_TIME   -> SimpleDateFormat("dd MMM HH:mm",      Locale.US).format(date).uppercase()
        NUMERIC_EU  -> SimpleDateFormat("dd.MM.yy",          Locale.US).format(date)
        NUMERIC_US  -> SimpleDateFormat("MM/dd/yy",          Locale.US).format(date)
        DD_MM_YY    -> SimpleDateFormat("dd MM yy",          Locale.US).format(date)
        MM_DD_YY    -> SimpleDateFormat("MM dd yy",          Locale.US).format(date)
        YY_MM_DD    -> SimpleDateFormat("''yy MM dd",        Locale.US).format(date)
        YYYY_MM_DD  -> SimpleDateFormat("yyyy MM dd",        Locale.US).format(date)
        DD_MM_YYYY  -> SimpleDateFormat("dd.MM.yyyy",        Locale.US).format(date)
        MM_DD_YYYY  -> SimpleDateFormat("MM/dd/yyyy",        Locale.US).format(date)
        ISO_8601    -> SimpleDateFormat("yyyy-MM-dd",        Locale.US).format(date)
        DD_MMM_YYYY -> SimpleDateFormat("dd MMM yyyy",       Locale.US).format(date).uppercase()
        MMM_YYYY    -> SimpleDateFormat("MMM yyyy",          Locale.US).format(date).uppercase()
        DATETIME_EU -> SimpleDateFormat("dd.MM.yy HH:mm",   Locale.US).format(date)
        DATETIME_US -> SimpleDateFormat("MM/dd/yy HH:mm",   Locale.US).format(date)
        DATETIME_ISO-> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(date)
        TIME_ONLY   -> SimpleDateFormat("HH:mm",             Locale.US).format(date)
    }

    fun next(): DateImprintStyle = entries[(ordinal + 1) % entries.size]
}

enum class DateImprintColor(val label: String, val hex: String) {
    AMBER("AMBER", "#FF6600"),
    RED("RED",     "#DD2222"),
    WHITE("WHITE", "#F0F0F0"),
    YELLOW("GOLD", "#FFE600"),
    GREEN("GREEN", "#00CC44"),
    CYAN("CYAN",   "#00CCEE"),
}

enum class DateImprintFont(val label: String) {
    LED("LED"),
    MONOSPACE("MONO"),
    BOLD("BOLD"),
    SERIF("SERIF"),
    CONDENSED("COND"),
}

enum class DateImprintPosition(val label: String) {
    BOTTOM_RIGHT("BOT-R"),
    BOTTOM_LEFT("BOT-L"),
    BOTTOM_CENTER("BOT-C"),
    TOP_RIGHT("TOP-R"),
    TOP_LEFT("TOP-L"),
}

data class DateImprintSettings(
    val enabled: Boolean = false,
    val style: DateImprintStyle = DateImprintStyle.CLASSIC,
    val color: DateImprintColor = DateImprintColor.AMBER,
    val font: DateImprintFont = DateImprintFont.LED,
    val sizePercent: Float = 2.0f,  // 1.0..4.0 — percent of image width
    val position: DateImprintPosition = DateImprintPosition.BOTTOM_RIGHT,
    val glowAmount: Int = 100,   // 0..100
    val blurAmount: Int = 50,    // 0..100
    val opacity: Int = 50,       // 0..100
    val blurRepeat: Int = 3,     // 1..20
)
