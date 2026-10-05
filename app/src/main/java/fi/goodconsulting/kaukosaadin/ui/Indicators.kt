package fi.goodconsulting.kaukosaadin.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.goodconsulting.kaukosaadin.device.DeviceKind

/** Power button for the saved TV: wake-only on LG, sleep on the Apple TV. The LED indicates
 *  registration, not power. */
@Composable
internal fun PowerDeck(
    ready: Boolean,
    powerEnabled: Boolean,
    kind: DeviceKind,
    txFlash: () -> Float,
    onPower: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        PowerKey(ready, powerEnabled, kind, onPower)
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(top = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TxLamp(txFlash)
            Text(
                text = "KAUKOSÄÄDIN",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
internal fun PowerKey(
    ready: Boolean,
    enabled: Boolean,
    kind: DeviceKind,
    onClick: () -> Unit,
) {
    val lg = kind == DeviceKind.Lg
    Column(
        modifier = Modifier.width(80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Led(ready)
        Key(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            face = PowerRed,
            elevation = 6.dp,
            haptic = HapticFeedbackConstants.LONG_PRESS,
            modifier =
                Modifier
                    .size(58.dp)
                    .semantics {
                        contentDescription = if (lg) "Wake TV" else "Sleep Apple TV"
                        stateDescription = if (ready) "Registration verified" else "Not connected"
                    },
        ) {
            PowerGlyph(OnPowerRed, Modifier.size(22.dp))
        }
        EngravedLabel(if (lg) "WAKE" else "SLEEP")
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

/** Registration LED: glowing green when verified, otherwise a dark unlit bead. */
@Composable
internal fun Led(isOn: Boolean) {
    val color by animateColorAsState(if (isOn) LedOn else LedOff, label = "led")
    Canvas(Modifier.size(7.dp)) {
        val r = size.minDimension / 2
        if (isOn) {
            drawCircle(
                brush = Brush.radialGradient(listOf(color.copy(alpha = 0.5f), Color.Transparent), center, r * 2.6f),
                radius = r * 2.6f,
            )
        }
        drawCircle(color, r)
        drawCircle(Color.White.copy(alpha = 0.55f), r * 0.32f, center + Offset(-r * 0.3f, -r * 0.3f))
    }
}

/** Smoked lens at the nose of the remote; flares red for each command. */
@Composable
internal fun TxLamp(flash: () -> Float) {
    Canvas(Modifier.size(width = 60.dp, height = 16.dp)) {
        val corner = CornerRadius(size.height / 2)
        drawRoundRect(TxLens, cornerRadius = corner)
        val f = flash()
        if (f > 0f) {
            drawRoundRect(
                brush =
                    Brush.radialGradient(
                        listOf(TxGlow.copy(alpha = f), TxGlow.copy(alpha = 0.25f * f), Color.Transparent),
                        center = center,
                        radius = size.width / 2,
                    ),
                cornerRadius = corner,
            )
        }
        drawRoundRect(
            brush =
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                    endY = size.height * 0.6f,
                ),
            cornerRadius = corner,
        )
        drawRoundRect(Color.Black.copy(alpha = 0.5f), cornerRadius = corner, style = Stroke(1.dp.toPx()))
    }
}

/** Small caps printed into the casing, with a one-pixel lip below. */
@Composable
internal fun EngravedLabel(text: String) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Text(
        text = text,
        style =
            MaterialTheme.typography.labelSmall.copy(
                shadow =
                    Shadow(
                        color = if (dark) Color.Black.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.85f),
                        offset = Offset(0f, 1.5f),
                    ),
            ),
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Staggered grid of punched holes at the tail of the casing. */
@Composable
internal fun SpeakerGrille() {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val hole = if (dark) Color.Black.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val lip = if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.9f)
    Canvas(Modifier.size(width = 140.dp, height = 18.dp)) {
        val rows = 3
        val cols = 16
        val dx = size.width / cols
        val dy = size.height / rows
        val r = 1.7.dp.toPx()
        for (row in 0 until rows) {
            val stagger = if (row % 2 == 1) dx / 2 else 0f
            for (col in 0 until cols - row % 2) {
                val c = Offset(dx * (col + 0.5f) + stagger, dy * (row + 0.5f))
                drawCircle(lip, r, c + Offset(0f, 0.8.dp.toPx()))
                drawCircle(hole, r, c)
            }
        }
    }
}
