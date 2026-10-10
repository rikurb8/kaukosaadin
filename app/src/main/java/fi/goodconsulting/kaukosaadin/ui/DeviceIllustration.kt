@file:Suppress("MagicNumber") // Illustration coordinates use a 320 × 160 artboard.

package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.DeviceKind

/** Decorative hardware sketches; the adjacent text supplies the instructions. */
@Composable
internal fun DeviceIllustration(
    modifier: Modifier = Modifier,
    kind: DeviceKind? = null,
) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.fillMaxWidth().height(160.dp)) {
        val scale = minOf(size.width / 320f, size.height / 160f)
        withTransform({
            translate((size.width - 320f * scale) / 2, (size.height - 160f * scale) / 2)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            when (kind) {
                null -> {
                    sketchTv(Offset(64f, 18f), 156f, colors.onSurface, colors.surface, colors.primary)
                    sketchRemote(colors.onSecondaryContainer, colors.secondaryContainer, colors.primary)
                    sketchBridge(Offset(246f, 78f), 58f, colors.onSurface, colors.primaryContainer, colors.primary)
                    sketchBox(Offset(124f, 130f), colors.onSurface, colors.surface, colors.primary)
                }
                DeviceKind.Lg, DeviceKind.AppleTv -> {
                    sketchTv(Offset(78f, 14f), 164f, colors.onSurface, colors.surface, colors.primary)
                    if (kind == DeviceKind.AppleTv) {
                        sketchBox(Offset(130f, 132f), colors.onSurface, colors.surface, colors.primary)
                    }
                }
                DeviceKind.Hue -> sketchBridge(Offset(112f, 32f), 96f, colors.onSurface, colors.surface, colors.primary)
            }
        }
    }
}

private fun DrawScope.sketchTv(
    origin: Offset,
    width: Float,
    ink: Color,
    paper: Color,
    accent: Color,
) {
    val frame = Size(width, width * 0.58f)
    drawRoundRect(ink, origin + Offset(5f, 5f), frame, CornerRadius(10f))
    drawRoundRect(paper, origin, frame, CornerRadius(10f))
    drawRoundRect(ink, origin, frame, CornerRadius(10f), style = Stroke(3f))
    drawRoundRect(accent, origin + Offset(9f, 9f), Size(frame.width - 18f, frame.height - 18f), CornerRadius(5f))
    drawCircle(paper, 10f, origin + Offset(width / 2, frame.height / 2))
    drawLine(ink, origin + Offset(width / 2, frame.height), origin + Offset(width / 2, frame.height + 12f), 3f, StrokeCap.Round)
    drawLine(
        ink,
        origin + Offset(width * 0.35f, frame.height + 12f),
        origin + Offset(width * 0.65f, frame.height + 12f),
        3f,
        StrokeCap.Round,
    )
}

private fun DrawScope.sketchBridge(
    origin: Offset,
    width: Float,
    ink: Color,
    paper: Color,
    accent: Color,
) {
    drawRoundRect(paper, origin, Size(width, width), CornerRadius(width * 0.22f))
    drawRoundRect(ink, origin, Size(width, width), CornerRadius(width * 0.22f), style = Stroke(3f))
    val button = origin + Offset(width / 2, width / 2)
    drawCircle(accent.copy(alpha = 0.12f), width * 0.32f, button)
    drawCircle(accent, width * 0.2f, button, style = Stroke(3f))
    drawCircle(accent, 2f, origin + Offset(width / 2, width * 0.16f))
}

private fun DrawScope.sketchBox(
    origin: Offset,
    ink: Color,
    paper: Color,
    accent: Color,
) {
    drawRoundRect(paper, origin, Size(60f, 16f), CornerRadius(6f))
    drawRoundRect(ink, origin, Size(60f, 16f), CornerRadius(6f), style = Stroke(3f))
    drawCircle(accent, 2f, origin + Offset(48f, 8f))
}

private fun DrawScope.sketchRemote(
    ink: Color,
    paper: Color,
    accent: Color,
) {
    rotate(-12f, Offset(36f, 100f)) {
        drawRoundRect(paper, Offset(18f, 58f), Size(36f, 82f), CornerRadius(14f))
        drawRoundRect(ink, Offset(18f, 58f), Size(36f, 82f), CornerRadius(14f), style = Stroke(3f))
        drawCircle(accent, 4f, Offset(36f, 72f))
        drawCircle(ink, 10f, Offset(36f, 98f), style = Stroke(3f))
        drawCircle(ink, 2f, Offset(36f, 98f))
        drawLine(ink, Offset(31f, 122f), Offset(41f, 122f), 3f, StrokeCap.Round)
    }
}
