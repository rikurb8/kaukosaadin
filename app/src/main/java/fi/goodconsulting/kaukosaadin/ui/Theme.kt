package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Paper, ink and punchy color blocks; shared by setup, settings and remote controls. */
internal val LightColors =
    lightColorScheme(
        background = Color(0xFFFFF8ED),
        onBackground = Color(0xFF201C29),
        surface = Color(0xFFFFFDF7),
        onSurface = Color(0xFF201C29),
        surfaceVariant = Color(0xFFE8E1F0),
        onSurfaceVariant = Color(0xFF60586B),
        outline = Color(0xFF30263E),
        tertiary = Color(0xFFD9F45B),
        onTertiary = Color(0xFF242B0E),
        tertiaryContainer = Color(0xFFD9F45B),
        onTertiaryContainer = Color(0xFF242B0E),
        primary = Color(0xFF6236D9),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFC9E5),
        onPrimaryContainer = Color(0xFF422034),
        secondaryContainer = Color(0xFFD9F45B),
        onSecondaryContainer = Color(0xFF242B0E),
    )

internal val DarkColors =
    darkColorScheme(
        background = Color(0xFF191620),
        onBackground = Color(0xFFFFF8ED),
        surface = Color(0xFF25202F),
        onSurface = Color(0xFFFFF8ED),
        surfaceVariant = Color(0xFF393143),
        onSurfaceVariant = Color(0xFFC8BED3),
        outline = Color(0xFFB8A9CA),
        tertiary = Color(0xFFD9F45B),
        onTertiary = Color(0xFF242B0E),
        tertiaryContainer = Color(0xFF394519),
        onTertiaryContainer = Color(0xFFD9F45B),
        primary = Color(0xFFC6ADFF),
        onPrimary = Color(0xFF28114D),
        primaryContainer = Color(0xFF553149),
        onPrimaryContainer = Color(0xFFFFD8ED),
        secondaryContainer = Color(0xFFCDE85B),
        onSecondaryContainer = Color(0xFF242B0E),
    )

internal val AppShapes =
    Shapes(
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(20.dp),
    )

private val BaseTypography = Typography()
internal val AppTypography =
    Typography(
        headlineLarge = BaseTypography.headlineLarge.copy(fontWeight = FontWeight.Black),
        headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.Black),
        headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
        titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
        titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.Bold),
        labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.Bold),
    )

internal val HackerManColors =
    darkColorScheme(
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
