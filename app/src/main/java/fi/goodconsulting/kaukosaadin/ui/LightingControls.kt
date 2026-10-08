@file:Suppress("MagicNumber") // Glow, glyph and bar geometry are drawn in fractions of their size.

package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.goodconsulting.kaukosaadin.device.hue.HueBrightness
import fi.goodconsulting.kaukosaadin.device.hue.HueLight
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Ink on a lit amber surface: the power key's glyph and the brightness bar's label. Dark in every theme. */
private val LitInk = Color(0xFF2A1A00)

/** How many member LEDs a collapsed room shows before the rest become a "+n". */
private const val MAX_LEDS = 10

/** A section heading in the remote's silkscreen style: spaced capitals and a count. */
@Composable
internal fun SectionLabel(
    title: String,
    count: Int,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A card whose face warms with [lit] (0 dark to 1 full): the fill leans towards the theme's lamp
 * colour and a soft glow blooms from the top-left corner, so a lit room reads as lit at a glance.
 */
@Composable
internal fun LightCard(
    lit: Float,
    content: @Composable () -> Unit,
) {
    val glow by animateFloatAsState(lit, label = "cardGlow")
    val lamp = MaterialTheme.colorScheme.tertiary
    val face = lerp(MaterialTheme.colorScheme.surface, lamp, 0.08f + 0.22f * glow).takeIf { glow > 0f }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(face ?: MaterialTheme.colorScheme.surface)
            .drawBehind {
                if (glow <= 0f) return@drawBehind
                drawRect(
                    Brush.radialGradient(
                        listOf(lamp.copy(alpha = 0.45f * glow), Color.Transparent),
                        center = Offset(size.width * 0.12f, 0f),
                        radius = size.width * 0.95f,
                    ),
                )
            }.animateContentSize(),
    ) { content() }
}

/** The row that opens and closes a card's lights: one LED per light, how many there are, and a chevron. */
@Composable
internal fun DrawerHandle(
    lights: List<HueLight>,
    open: Boolean,
    name: String,
    onClick: () -> Unit,
) {
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = if (open) "Hide lights" else "Show lights", onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "Lights in $name"
                stateDescription = if (open) "Expanded" else "Collapsed"
            }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            lights.take(MAX_LEDS).forEach { LampLed(it.on, barLevel(null, it.brightness)) }
            if (lights.size > MAX_LEDS) {
                Text(
                    "+${lights.size - MAX_LEDS}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (lights.size == 1) "1 light" else "${lights.size} lights",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Chevron(
            MaterialTheme.colorScheme.onSurfaceVariant,
            Modifier
                .padding(start = 6.dp, end = 8.dp)
                .rotate(turn),
        )
    }
}

/** The value a bar shows: the operator's draft while dragging, else the bridge's brightness, within the slider's range. */
internal fun barLevel(
    draft: Int?,
    brightness: Double?,
): Int = (draft ?: brightness?.roundToInt() ?: HueBrightness.MIN).coerceIn(HueBrightness.MIN, HueBrightness.MAX)

/**
 * A round power key that glows in the lamp colour while [on]. It is a switch to accessibility and
 * sends one on/off command per tap through [onToggle]; it is disabled while a command for its target
 * is in flight.
 */
@Composable
internal fun LampKey(
    on: Boolean,
    enabled: Boolean,
    name: String,
    size: Dp,
    onToggle: () -> Unit,
) {
    val lamp = MaterialTheme.colorScheme.tertiary
    val ink = if (on) LitInk else MaterialTheme.colorScheme.onSecondaryContainer
    Box(
        Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.55f)
            .drawBehind {
                // A lit key throws a soft halo of its own light; an unlit one just sits in the panel.
                if (on) {
                    drawCircle(
                        Brush.radialGradient(listOf(lamp.copy(alpha = 0.55f), Color.Transparent)),
                        this.size.minDimension * 0.85f,
                    )
                }
            }.background(
                if (on) {
                    Brush.radialGradient(listOf(lamp.tint(0.45f), lamp))
                } else {
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.secondaryContainer.tint(0.15f),
                            MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    )
                },
                CircleShape,
            ).toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.42f)) {
            val stroke = Stroke(width = this.size.minDimension * 0.13f, cap = StrokeCap.Round)
            val inset = stroke.width / 2
            drawArc(
                ink,
                startAngle = -60f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = Offset(inset, inset + this.size.height * 0.06f),
                size = Size(this.size.width - stroke.width, this.size.height - stroke.width),
                style = stroke,
            )
            drawLine(
                ink,
                Offset(center.x, 0f + inset),
                Offset(center.x, center.y),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * A chunky brightness bar: a lamp-coloured fill up to [level] with its percentage printed inside. It is
 * a Material slider underneath, so dragging only reports through [onDrag] and the one command goes on
 * release through [onRelease]. Off, it shows the last level greyed out and refuses input.
 */
@OptIn(ExperimentalMaterial3Api::class) // The slider's track and thumb slots.
@Composable
internal fun BrightnessBar(
    level: Int,
    on: Boolean,
    enabled: Boolean,
    height: Dp,
    name: String,
    onDrag: (Int) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = (level - HueBrightness.MIN).toFloat() / (HueBrightness.MAX - HueBrightness.MIN)
    val lamp = MaterialTheme.colorScheme.tertiary
    val well = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val shape = RoundedCornerShape(height * 0.36f)
    val tall = height >= 40.dp
    Slider(
        value = level.toFloat(),
        onValueChange = { onDrag(it.roundToInt()) },
        onValueChangeFinished = onRelease,
        enabled = enabled,
        valueRange = HueBrightness.MIN.toFloat()..HueBrightness.MAX.toFloat(),
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = "$name brightness" },
        thumb = {
            Box(
                Modifier
                    .size(width = 4.dp, height = height * 0.56f)
                    .background(if (on) LitInk.copy(alpha = 0.55f) else Color.Transparent, RoundedCornerShape(2.dp)),
            )
        },
        track = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(shape)
                    .background(well)
                    .drawBehind {
                        val fill =
                            if (on) {
                                Brush.horizontalGradient(
                                    listOf(lamp.copy(alpha = 0.7f), lamp),
                                )
                            } else {
                                Brush.linearGradient(listOf(dim, dim))
                            }
                        drawRect(fill, size = Size(size.width * fraction, size.height))
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                // The label is printed twice and each copy clipped at the fill's edge, so the part over
                // the lamp colour is dark ink and the rest is the surface's ink, wherever the edge falls.
                val label = if (on) "$level%" else "Off"
                val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                BarLabel(label, if (on) LitInk else ink, tall, Modifier.clipX(0f, fraction))
                BarLabel(label, ink, tall, Modifier.clipX(if (on) fraction else 0f, 1f))
            }
        },
    )
}

@Composable
private fun BarLabel(
    text: String,
    color: Color,
    tall: Boolean,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        Text(
            text,
            Modifier.padding(start = if (tall) 16.dp else 10.dp),
            style = if (tall) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = color,
        )
    }
}

/** Draws only the horizontal band from [from] to [to], as fractions of the width. */
private fun Modifier.clipX(
    from: Float,
    to: Float,
) = drawWithContent { clipRect(left = size.width * from, right = size.width * to) { this@drawWithContent.drawContent() } }

/** A small status LED: lit in the lamp colour with a halo that grows with [level], or a dark pip when off. */
@Composable
internal fun LampLed(
    on: Boolean,
    level: Int,
    modifier: Modifier = Modifier,
) {
    val lamp = MaterialTheme.colorScheme.tertiary
    val pip = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Canvas(modifier.size(10.dp)) {
        val r = size.minDimension / 2
        if (!on) {
            drawCircle(pip, r * 0.8f)
            return@Canvas
        }
        val strength = 0.4f + 0.6f * level / HueBrightness.MAX
        drawCircle(Brush.radialGradient(listOf(lamp.copy(alpha = 0.55f * strength), Color.Transparent), radius = r * 2f), r * 2f)
        drawCircle(lamp, r * 0.8f)
        drawCircle(Color.White.copy(alpha = 0.5f * strength), r * 0.3f, center - Offset(r * 0.2f, r * 0.2f))
    }
}

/**
 * The favorite control: a star, filled while kept. It names the action it performs, so the operator
 * can tell whether the row is already kept. It is never disabled: keeping a light, room or zone writes
 * to the phone, so it needs no bridge and no command slot.
 */
@Composable
internal fun FavoriteStar(
    favorite: Boolean,
    name: String,
    size: Dp = 22.dp,
    onClick: () -> Unit,
) {
    val ink = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = if (favorite) "Unfavorite $name" else "Favorite $name" },
    ) {
        Canvas(Modifier.size(size)) {
            val star = Path()
            val c = center
            val outer = this.size.minDimension / 2
            val inner = outer * 0.45f
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) outer else inner
                val a = -PI / 2 + i * PI / 5
                val p = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat() + outer * 0.06f)
                if (i == 0) star.moveTo(p.x, p.y) else star.lineTo(p.x, p.y)
            }
            star.close()
            if (favorite) drawPath(star, ink)
            drawPath(star, ink, style = Stroke(width = outer * 0.16f, join = StrokeJoin.Round))
        }
    }
}

/** A downward chevron; the drawer rotates it to point up while open. */
@Composable
private fun Chevron(
    ink: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(14.dp)) {
        val path =
            Path().apply {
                moveTo(size.width * 0.15f, size.height * 0.35f)
                lineTo(size.width * 0.5f, size.height * 0.7f)
                lineTo(size.width * 0.85f, size.height * 0.35f)
            }
        drawPath(path, ink, style = Stroke(width = size.minDimension * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
