package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

internal fun Color.tint(fraction: Float) = lerp(this, Color.White, fraction)

internal fun Color.shade(fraction: Float) = lerp(this, Color.Black, fraction)
