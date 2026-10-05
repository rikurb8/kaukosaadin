package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Braun-dieter-rams palette: cream casing, charcoal keys, one orange accent.
 * Keys and cards borrow their colors from `secondaryContainer`/`surface`.
 */
internal val LightColors = lightColorScheme(
    background = Color(0xFFE9E2D0),
    onBackground = Color(0xFF2A261D),
    surface = Color(0xFFF5F0E1),
    onSurface = Color(0xFF2A261D),
    surfaceVariant = Color(0xFFD8D0BB),
    onSurfaceVariant = Color(0xFF6E6753),
    outline = Color(0xFFB9AE93),
    tertiary = Color(0xFFFFB84D),
    primary = Color(0xFFE3600F),
    onPrimary = Color(0xFFFFF7EC),
    primaryContainer = Color(0xFFF7DFC8),
    onPrimaryContainer = Color(0xFF4A2000),
    secondaryContainer = Color(0xFFDCD3BD),
    onSecondaryContainer = Color(0xFF332E22),
)

internal val DarkColors = darkColorScheme(
    background = Color(0xFF1C1914),
    onBackground = Color(0xFFEDE6D6),
    surface = Color(0xFF262219),
    onSurface = Color(0xFFEDE6D6),
    surfaceVariant = Color(0xFF322C21),
    onSurfaceVariant = Color(0xFFA39A84),
    outline = Color(0xFF4E463A),
    tertiary = Color(0xFFFFB84D),
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF331300),
    primaryContainer = Color(0xFF4A2A12),
    onPrimaryContainer = Color(0xFFFFC08F),
    secondaryContainer = Color(0xFF4A4232),
    onSecondaryContainer = Color(0xFFE4DCC6),
)

internal val HackerManColors = darkColorScheme(
    background = Color(0xFF030805),
    onBackground = Color(0xFFB6FFC5),
    surface = Color(0xFF07110B),
    onSurface = Color(0xFFB6FFC5),
    surfaceVariant = Color(0xFF102419),
    onSurfaceVariant = Color(0xFF8DC69D),
    outline = Color(0xFF44885A),
    primary = Color(0xFF39FF70),
    onPrimary = Color(0xFF002109),
    primaryContainer = Color(0xFF123E20),
    onPrimaryContainer = Color(0xFFB6FFC5),
    secondaryContainer = Color(0xFF12321E),
    onSecondaryContainer = Color(0xFFB6FFC5),
    tertiary = Color(0xFF39FF70),
)
