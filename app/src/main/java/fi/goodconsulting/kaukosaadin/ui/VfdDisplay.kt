package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Amber VFD behind a smoked bezel: annunciators for target, readiness and TX on
 * top, the targeted device in large glowing type, and the command echo with
 * a blinking cursor.
 */
@Composable
internal fun VfdDisplay(
    target: Target,
    tvName: String,
    ready: Boolean,
    status: String,
    txFlash: () -> Float,
) {
    val bezel = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = bezel)
            .clip(bezel)
            .background(Brush.verticalGradient(listOf(LcdBezelTop, LcdBezelBottom)))
            .padding(4.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(LcdBackdrop)
                    .vfdGlass()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Annunciator(target.annunciator, lit = true)
                Spacer(Modifier.weight(1f))
                Annunciator("READY", lit = ready)
                Annunciator("SETUP", lit = !ready)
                Annunciator("TX", lit = txFlash() > 0.05f)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = tvName.uppercase(Locale.US),
                    style = vfdStyle(MaterialTheme.typography.headlineSmall, if (ready) LcdText else LcdDim),
                    letterSpacing = 2.sp,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = target.platform,
                    style = vfdStyle(MaterialTheme.typography.labelMedium, LcdDim),
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = status,
                    style = vfdStyle(MaterialTheme.typography.bodySmall, LcdDim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                BlinkingCursor()
            }
        }
    }
}

@Composable
internal fun Annunciator(
    text: String,
    lit: Boolean,
) {
    Text(
        text = text,
        style =
            if (lit) {
                vfdStyle(MaterialTheme.typography.labelSmall, LcdText)
            } else {
                MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, color = LcdGhost)
            },
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
}

@Composable
internal fun BlinkingCursor() {
    val blink by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        // Step easing: fully on for half the period, fully off for the other.
        animationSpec = infiniteRepeatable(tween(durationMillis = 1060, easing = { if (it < 0.5f) 0f else 1f })),
        label = "cursorBlink",
    )
    Box(
        Modifier
            .padding(start = 3.dp)
            .size(width = 7.dp, height = 12.dp)
            .graphicsLayer { alpha = blink }
            .background(LcdDim),
    )
}

/** Monospace type with a soft phosphor bloom in its own color. */
internal fun vfdStyle(
    base: TextStyle,
    color: Color,
) = base.copy(
    fontFamily = FontFamily.Monospace,
    color = color,
    shadow = Shadow(color = color.copy(alpha = 0.75f), blurRadius = 14f),
)

/** Where the glass glare has faded out, as a fraction of the panel height. */
private const val GLARE_FADE_STOP = 0.5f

/** Scanlines and a glass glare over the display content. */
internal fun Modifier.vfdGlass() =
    drawWithContent {
        drawContent()
        val step = 3.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(Color.Black.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.07f),
                GLARE_FADE_STOP to Color.Transparent,
            ),
        )
    }
