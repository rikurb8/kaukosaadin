package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Screw-head proportions, all relative to the head radius. */
private const val SCREW_HIGHLIGHT_OFFSET = 0.4f
private const val SCREW_SHADE = 0.2f

/** Half-length of the slot cut across the screw head, relative to the head radius. */
private const val SCREW_SLOT_HALF_LENGTH = 0.65f

/** Top-centre X of the fit-to-height scale, so the remote shrinks from its top edge. */
private const val FIT_ORIGIN_X = 0.5f

/** Slotted screw head, lit from the top-left. */
internal fun DrawScope.screw(
    center: Offset,
    radius: Float,
    angle: Float,
    base: Color,
) {
    drawCircle(
        Brush.radialGradient(
            listOf(base.tint(SCREW_HIGHLIGHT_OFFSET), base.shade(SCREW_SHADE)),
            center = center - Offset(radius * SCREW_HIGHLIGHT_OFFSET, radius * SCREW_HIGHLIGHT_OFFSET),
            radius = radius * 1.6f,
        ),
        radius,
        center,
    )
    drawCircle(Color.Black.copy(alpha = 0.3f), radius, center, style = Stroke(0.8.dp.toPx()))
    rotate(angle, center) {
        drawLine(
            Color.Black.copy(alpha = 0.5f),
            center - Offset(radius * SCREW_SLOT_HALF_LENGTH, 0f),
            center + Offset(radius * SCREW_SLOT_HALF_LENGTH, 0f),
            strokeWidth = 1.3.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Spreads children evenly over the column's height, never closer than
 * [atLeast]. Unlike SpaceBetween it keeps a real gap when the content is
 * taller than the space.
 */
internal fun spacedEvenly(atLeast: Dp) =
    object : Arrangement.Vertical {
        override val spacing = atLeast

        override fun Density.arrange(
            totalSize: Int,
            sizes: IntArray,
            outPositions: IntArray,
        ) {
            val gaps = (sizes.size - 1).coerceAtLeast(1)
            val gap = maxOf(atLeast.toPx(), (totalSize - sizes.sum()).toFloat() / gaps)
            var y = 0f
            sizes.forEachIndexed { i, size ->
                outPositions[i] = y.roundToInt()
                y += size + gap
            }
        }
    }

/**
 * Measures the remote unconstrained and scales it down (never up) so it fits
 * [availableHeight], but no further than [MIN_FIT_SCALE]. The caller must be
 * scrollable: past the floor the remote stays taller than the space, so the
 * user's font setting keeps growing the text instead of being scaled away.
 */
internal fun Modifier.fitToHeight(availableHeight: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
        val scale = fitScale(placeable.height, availableHeight.roundToPx())
        val width = constraints.maxWidth
        layout(width, (placeable.height * scale).roundToInt()) {
            placeable.placeWithLayer((width - placeable.width) / 2, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(FIT_ORIGIN_X, 0f)
            }
        }
    }

/**
 * Smallest shrink the remote may take to fit; anything taller scrolls instead.
 * Text sizes are `sp`, so the scale races the system font setting: keep this
 * near 1, or at 1 to never shrink and always scroll.
 */
internal const val MIN_FIT_SCALE = 0.85f

/** 1 when [contentHeight] fits in [availableHeight]; otherwise the shrink factor, floored at [MIN_FIT_SCALE]. */
internal fun fitScale(
    contentHeight: Int,
    availableHeight: Int,
): Float =
    if (contentHeight <= availableHeight) {
        1f
    } else {
        (availableHeight.toFloat() / contentHeight).coerceAtLeast(MIN_FIT_SCALE)
    }

internal fun Color.tint(fraction: Float) = lerp(this, Color.White, fraction)

internal fun Color.shade(fraction: Float) = lerp(this, Color.Black, fraction)
