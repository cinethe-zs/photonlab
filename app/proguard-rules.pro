# Hilt
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }

# Keep LutParser data classes
-keep class com.photonlab.domain.model.** { *; }

# Kotlin Serialization (if added later)
-keepattributes *Annotation*
