@file:Suppress("MagicNumber") // Glyph geometry is drawn in fractions of its canvas.

package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.DeviceKind

/**
 * A device kind's small drawn icon in a round badge: a TV for an LG TV, a set-top box for an Apple
 * TV, a bulb for a Hue Bridge. Drawn rather than loaded so it takes every theme's colours.
 */
@Composable
internal fun KindGlyph(
    kind: DeviceKind,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val ink = MaterialTheme.colorScheme.onSecondaryContainer
    Box(
        modifier
            .size(size)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * GLYPH_FRACTION)) {
            when (kind) {
                DeviceKind.Lg -> drawTv(ink)
                DeviceKind.AppleTv -> drawSetTopBox(ink)
                DeviceKind.Hue -> drawBulb(ink)
            }
        }
    }
}

private fun DrawScope.line() = Stroke(width = size.minDimension * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.drawTv(ink: Color) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        ink,
        topLeft = Offset(w * 0.05f, h * 0.14f),
        size = Size(w * 0.9f, h * 0.6f),
        cornerRadius = CornerRadius(w * 0.08f),
        style = line(),
    )
    drawLine(ink, Offset(w * 0.32f, h * 0.9f), Offset(w * 0.68f, h * 0.9f), strokeWidth = line().width, cap = StrokeCap.Round)
}

private fun DrawScope.drawSetTopBox(ink: Color) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        ink,
        topLeft = Offset(w * 0.05f, h * 0.36f),
        size = Size(w * 0.9f, h * 0.34f),
        cornerRadius = CornerRadius(w * 0.1f),
        style = line(),
    )
    drawCircle(ink, radius = w * 0.06f, center = Offset(w * 0.26f, h * 0.53f))
    drawLine(ink, Offset(w * 0.2f, h * 0.84f), Offset(w * 0.8f, h * 0.84f), strokeWidth = line().width, cap = StrokeCap.Round)
}

private fun DrawScope.drawBulb(ink: Color) {
    val w = size.width
    val h = size.height
    val bulb =
        Path().apply {
            moveTo(w * 0.36f, h * 0.7f)
            cubicTo(w * 0.36f, h * 0.56f, w * 0.18f, h * 0.5f, w * 0.18f, h * 0.36f)
            cubicTo(w * 0.18f, h * 0.18f, w * 0.32f, h * 0.04f, w * 0.5f, h * 0.04f)
            cubicTo(w * 0.68f, h * 0.04f, w * 0.82f, h * 0.18f, w * 0.82f, h * 0.36f)
            cubicTo(w * 0.82f, h * 0.5f, w * 0.64f, h * 0.56f, w * 0.64f, h * 0.7f)
            close()
        }
    drawPath(bulb, ink, style = line())
    drawLine(ink, Offset(w * 0.38f, h * 0.84f), Offset(w * 0.62f, h * 0.84f), strokeWidth = line().width, cap = StrokeCap.Round)
    drawLine(ink, Offset(w * 0.43f, h * 0.96f), Offset(w * 0.57f, h * 0.96f), strokeWidth = line().width, cap = StrokeCap.Round)
}

/** A drawn tick, for the device the picker currently drives. */
@Composable
internal fun CheckGlyph(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(18.dp)) {
        val tick =
            Path().apply {
                moveTo(size.width * 0.15f, size.height * 0.55f)
                lineTo(size.width * 0.4f, size.height * 0.78f)
                lineTo(size.width * 0.85f, size.height * 0.25f)
            }
        drawPath(tick, color, style = line())
    }
}

/** Three stacked dots: the overflow menu affordance. */
@Composable
internal fun MoreGlyph(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(20.dp)) {
        val r = size.minDimension * 0.09f
        listOf(0.2f, 0.5f, 0.8f).forEach { drawCircle(color, radius = r, center = Offset(size.width / 2, size.height * it)) }
    }
}

/** A small rounded tag such as "Added" or "In use". */
@Composable
internal fun Tag(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * One device as a card: its kind's glyph, name and a short subtitle, an optional [tag] and
 * [trailing] control. Tappable when [onClick] is set; [dimmed] fades it, e.g. for one already added.
 */
@Composable
internal fun DeviceCard(
    kind: DeviceKind,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    dimmed: Boolean = false,
    tag: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .alpha(if (dimmed) DIMMED_ALPHA else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            KindGlyph(kind)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            tag?.let { Tag(it) }
            trailing?.invoke()
        }
    }
}

private const val GLYPH_FRACTION = 0.5f

private const val DIMMED_ALPHA = 0.55f
