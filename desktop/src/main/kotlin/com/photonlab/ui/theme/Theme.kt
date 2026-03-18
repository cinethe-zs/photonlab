package com.photonlab.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = Primary, onPrimary = OnPrimary, primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer, secondary = Secondary, onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer, onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary, onTertiary = OnTertiary, tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer, error = Error, onError = OnError,
    errorContainer = ErrorContainer, onErrorContainer = OnErrorContainer, background = Background,
    onBackground = OnBackground, surface = Surface, onSurface = OnSurface,
    surfaceVariant = SurfaceVariant, onSurfaceVariant = OnSurfaceVariant, outline = Outline,
    outlineVariant = OutlineVariant, scrim = Scrim, inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface, inversePrimary = InversePrimary,
)

private val LightColorScheme = lightColorScheme(
    primary = InversePrimary, onPrimary = OnPrimaryContainer,
    primaryContainer = PrimaryContainer, onPrimaryContainer = OnPrimary,
    background = InverseSurface, onBackground = InverseOnSurface,
    surface = InverseSurface, onSurface = InverseOnSurface,
)

@Composable
fun PhotonLabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography  = Typography,
        content     = content,
    )
}
