package com.feiying.basketball.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Kept only for the light-baseline screenshot; the app uses [BlackColors] by default.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFC1440E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCB),
    onPrimaryContainer = Color(0xFF3B0A00),
    secondary = Color(0xFF77574C),
    background = Color(0xFFFFF8F5),
    surface = Color(0xFFFFF8F5),
    surfaceVariant = Color(0xFFF5DED4),
    onSurfaceVariant = Color(0xFF53433C),
)

/**
 * The app's black theme: near-black backgrounds, charcoal cards and an orange accent.
 * It is the default regardless of the system theme.
 */
private val BlackColors = darkColorScheme(
    primary = Color(0xFFFF7A3D),
    onPrimary = Color(0xFF2A0D00),
    primaryContainer = Color(0xFF5C2200),
    onPrimaryContainer = Color(0xFFFFDCC8),
    secondary = Color(0xFFFFB59A),
    onSecondary = Color(0xFF2A1208),
    secondaryContainer = Color(0xFF4A2A1E),
    onSecondaryContainer = Color(0xFFFFDCC8),
    tertiary = Color(0xFFFFC46B),
    onTertiary = Color(0xFF2A1A00),
    tertiaryContainer = Color(0xFF4A3200),
    onTertiaryContainer = Color(0xFFFFE0A8),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000),
    errorContainer = Color(0xFF5C0000),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF050506),
    onBackground = Color(0xFFF4EEEA),
    surface = Color(0xFF0B0B0D),
    onSurface = Color(0xFFF4EEEA),
    surfaceVariant = Color(0xFF1B1B1F),
    onSurfaceVariant = Color(0xFFC9C3BE),
    outline = Color(0xFF6B6560),
    outlineVariant = Color(0xFF3A3735),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2A2A2E),
    inverseOnSurface = Color(0xFFF4EEEA),
    inversePrimary = Color(0xFFFF9A6B),
    surfaceDim = Color(0xFF050506),
    surfaceBright = Color(0xFF2A2A2E),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0B0B0D),
    surfaceContainer = Color(0xFF111114),
    surfaceContainerHigh = Color(0xFF18181C),
    surfaceContainerHighest = Color(0xFF222226),
)

@Composable
fun BasketballTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) BlackColors else LightColors,
        content = content,
    )
}
