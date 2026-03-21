package com.photonlab.platform

import java.util.prefs.Preferences

/**
 * Simple key-value preferences backed by java.util.prefs.Preferences.
 * On Linux this stores data in ~/.java/.userPrefs/com/photonlab/.
 */
object DesktopPreferences {

    private val prefs: Preferences = Preferences.userRoot().node("com/photonlab")

    fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    fun putInt(key: String, value: Int) { prefs.putInt(key, value); prefs.flush() }

    fun getString(key: String, default: String): String = prefs.get(key, default)

    fun putString(key: String, value: String) { prefs.put(key, value); prefs.flush() }
}
