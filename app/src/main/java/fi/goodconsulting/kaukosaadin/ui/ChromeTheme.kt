package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** Smoked VFD panel with theme-colored phosphor. */
internal val LcdBackdrop = Color(0xFF1B160D)

internal val LcdBezelTop = Color(0xFF0A0805)

internal val LcdBezelBottom = Color(0xFF2E281C)

internal val LcdText: Color
    @Composable get() = MaterialTheme.colorScheme.tertiary

internal val LcdDim: Color
    @Composable get() = lerp(LcdText, LcdBackdrop, 0.35f)

internal val LcdGhost: Color
    @Composable get() = lerp(LcdText, LcdBackdrop, 0.85f)

/** Printed-plastic hardware colors, identical in all themes. */
internal val PowerRed = Color(0xFFC8372B)

internal val OnPowerRed = Color(0xFFFFEDE6)

internal val LedOn = Color(0xFF8FE04F)

internal val LedOff = Color(0xFF4A4436)

internal val TxLens = Color(0xFF1E1112)

internal val TxGlow = Color(0xFFFF3B2F)

/**
 * Dial diameter bounds. On a phone the dial grows with the screen width; the
 * OK key and arrow hit areas are fixed fractions of it.
 */
internal val MinDialSize = 216.dp

internal val MaxDialSize = 264.dp
