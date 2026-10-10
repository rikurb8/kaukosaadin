package fi.goodconsulting.kaukosaadin.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.goodconsulting.kaukosaadin.device.DeviceKind

/** Deliberately separate from navigation: wake-only on LG, sleep-only on Apple TV. */
@Composable
internal fun PowerKey(
    enabled: Boolean,
    kind: DeviceKind,
    onClick: () -> Unit,
) {
    val lg = kind == DeviceKind.Lg
    val colors = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Key(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(18.dp),
            face = colors.primaryContainer,
            haptic = HapticFeedbackConstants.LONG_PRESS,
            modifier =
                Modifier.size(52.dp).semantics {
                    contentDescription = if (lg) "Wake TV" else "Sleep Apple TV"
                },
        ) {
            PowerGlyph(if (enabled) colors.onPrimaryContainer else colors.onSurfaceVariant, Modifier.size(22.dp))
        }
        Text(if (lg) "Wake" else "Sleep", style = MaterialTheme.typography.labelMedium)
    }
}

/** IEC standby symbol: an open ring with a bar through the gap. */
@Composable
internal fun PowerGlyph(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.13f
        val inset = stroke / 2
        drawArc(
            color = color,
            startAngle = -58f,
            sweepAngle = 296f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(center.x, 0f),
            end = Offset(center.x, center.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Play triangle followed by two pause bars, as printed on media remotes. */
@Composable
internal fun PlayPauseGlyph(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val h = size.height
        val play =
            Path().apply {
                moveTo(0f, 0f)
                lineTo(h * 0.9f, h / 2)
                lineTo(0f, h)
                close()
            }
        drawPath(play, color)
        val bar = h * 0.28f
        val left = size.width - bar * 3
        drawRect(color, Offset(left, 0f), Size(bar, h))
        drawRect(color, Offset(left + bar * 2, 0f), Size(bar, h))
    }
}

/** Small, widely spaced wordmark for the welcome screen. */
@Composable
internal fun EngravedLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
