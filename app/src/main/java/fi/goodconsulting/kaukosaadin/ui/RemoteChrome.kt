package fi.goodconsulting.kaukosaadin.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.goodconsulting.kaukosaadin.device.companion.PressAction
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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

/** Apple TV Back (Menu) and Home also take double tap and 1 s hold, like the Siri Remote. */
internal fun gestures(target: Target, key: RemoteKey) =
    target == Target.AppleTv && (key == RemoteKey.Back || key == RemoteKey.Home)

/** Wheel plus Back/Home/Play-Pause keys; shared by every layout. */
@Composable
internal fun RemoteKeys(
    dialSize: Dp,
    target: Target,
    navigationEnabled: Boolean,
    onPress: (RemoteKey, PressAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Dpad(
            diameter = dialSize,
            enabled = navigationEnabled,
            onPress = { onPress(it, PressAction.Tap) },
        )
        // Siri Remote order: Back and Home side by side, Play/Pause below.
        val pillRows = if (target == Target.AppleTv) {
            listOf(listOf(RemoteKey.Back, RemoteKey.Home), listOf(RemoteKey.PlayPause))
        } else {
            listOf(listOf(RemoteKey.Back))
        }
        val pillWidth = (dialSize - 24.dp - 12.dp * (pillRows[0].size - 1)) / pillRows[0].size
        val legend = if (navigationEnabled) colors.onSecondaryContainer else colors.onSurfaceVariant
        pillRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    Key(
                        onClick = { onPress(key, PressAction.Tap) },
                        onDoubleClick = if (gestures(target, key)) ({ onPress(key, PressAction.DoubleTap) }) else null,
                        onLongClick = if (gestures(target, key)) ({ onPress(key, PressAction.Hold) }) else null,
                        shape = RoundedCornerShape(50),
                        face = colors.secondaryContainer,
                        enabled = navigationEnabled,
                        elevation = 3.dp,
                        modifier = Modifier
                            .size(width = pillWidth, height = 44.dp)
                            .then(if (key == RemoteKey.PlayPause) Modifier.semantics { contentDescription = "Play/Pause" } else Modifier),
                    ) {
                        if (key == RemoteKey.PlayPause) {
                            PlayPauseGlyph(legend, Modifier.size(width = 30.dp, height = 14.dp))
                        } else {
                            Text(
                                text = key.name.uppercase(Locale.US),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 2.sp,
                                color = legend,
                            )
                        }
                    }
                }
            }
        }
        // Keep the Play/Pause row's footprint: fitToHeight must not resize the
        // entire remote when switching to a target with fewer keys.
        if (target == Target.Lg) Spacer(Modifier.height(44.dp))
    }
}

/** Wake button for the saved TV; the LED indicates registration, not power. */
@Composable
internal fun PowerDeck(
    ready: Boolean,
    wakeEnabled: Boolean,
    txFlash: () -> Float,
    onWake: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        PowerKey(ready, wakeEnabled, onWake)
        Column(
            modifier = Modifier
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
internal fun PowerKey(ready: Boolean, enabled: Boolean, onClick: () -> Unit) {
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
            modifier = Modifier
                .size(58.dp)
                .semantics {
                    contentDescription = "Wake TV"
                    stateDescription = if (ready) "Registration verified" else "Not connected"
                },
        ) {
            PowerGlyph(OnPowerRed, Modifier.size(22.dp))
        }
        EngravedLabel("WAKE")
    }
}

/** IEC standby symbol: an open ring with a bar through the gap. */
@Composable
internal fun PowerGlyph(color: Color, modifier: Modifier = Modifier) {
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
internal fun PlayPauseGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val h = size.height
        val play = Path().apply {
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
                brush = Brush.radialGradient(
                    listOf(TxGlow.copy(alpha = f), TxGlow.copy(alpha = 0.25f * f), Color.Transparent),
                    center = center,
                    radius = size.width / 2,
                ),
                cornerRadius = corner,
            )
        }
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                endY = size.height * 0.6f,
            ),
            cornerRadius = corner,
        )
        drawRoundRect(Color.Black.copy(alpha = 0.5f), cornerRadius = corner, style = Stroke(1.dp.toPx()))
    }
}

/**
 * Amber VFD behind a smoked bezel: annunciators for target, readiness and TX on
 * top, the targeted device in large glowing type, and the command echo with
 * a blinking cursor.
 */
@Composable
internal fun VfdDisplay(target: Target, tvName: String, ready: Boolean, status: String, txFlash: () -> Float) {
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
            modifier = Modifier
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
internal fun Annunciator(text: String, lit: Boolean) {
    Text(
        text = text,
        style = if (lit) vfdStyle(MaterialTheme.typography.labelSmall, LcdText)
        else MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, color = LcdGhost),
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
internal fun vfdStyle(base: TextStyle, color: Color) = base.copy(
    fontFamily = FontFamily.Monospace,
    color = color,
    shadow = Shadow(color = color.copy(alpha = 0.75f), blurRadius = 14f),
)

/** Scanlines and a glass glare over the display content. */
internal fun Modifier.vfdGlass() = drawWithContent {
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
            0.5f to Color.Transparent,
        ),
    )
}

/**
 * One-piece navigation wheel: a knurled bezel, four arrows printed on the face,
 * and a raised OK key seated in a recessed well. The quarter under a held
 * arrow darkens as if the wheel tilts. Until the target is ready, the whole
 * wheel greys out and refuses input.
 */
@Composable
internal fun Dpad(
    diameter: Dp,
    enabled: Boolean,
    onPress: (RemoteKey) -> Unit,
) {
    val okKeySize = diameter * 0.41f
    val arrowHitSize = diameter / 3
    val colors = MaterialTheme.colorScheme
    val face = if (enabled) colors.secondaryContainer else colors.surfaceVariant
    val glyph = if (enabled) colors.onSecondaryContainer else colors.onSurfaceVariant
    val interactions = remember { Direction.entries.associateWith { MutableInteractionSource() } }
    val held = Direction.entries.filter { interactions.getValue(it).collectIsPressedAsState().value }
    Box(
        modifier = Modifier
            .size(diameter)
            .alpha(if (enabled) 1f else 0.5f)
            .shadow(elevation = 10.dp, shape = CircleShape),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val r = size.minDimension / 2
            // Bezel with knurling around the rim.
            drawCircle(Brush.verticalGradient(listOf(face.tint(0.2f), face.shade(0.25f))), r)
            val ticks = 96
            val outer = r - 2.dp.toPx()
            val inner = r - 8.dp.toPx()
            repeat(ticks) { i ->
                val a = (2 * PI * i / ticks).toFloat()
                val dir = Offset(cos(a), sin(a))
                drawLine(
                    Color.Black.copy(alpha = 0.16f),
                    center + dir * inner,
                    center + dir * outer,
                    strokeWidth = 1.2.dp.toPx(),
                )
            }
            // Face.
            val faceR = r - 11.dp.toPx()
            drawCircle(Brush.verticalGradient(listOf(face.tint(0.1f), face.shade(0.06f))), faceR)
            held.forEach {
                drawArc(
                    color = Color.Black.copy(alpha = 0.1f),
                    startAngle = it.wedgeStart,
                    sweepAngle = 90f,
                    useCenter = true,
                    topLeft = center - Offset(faceR, faceR),
                    size = Size(faceR * 2, faceR * 2),
                )
            }
            drawCircle(Color.Black.copy(alpha = 0.22f), faceR, style = Stroke(1.dp.toPx()))
            // Recessed well: dark at the top, lit at the bottom lip.
            val wellR = okKeySize.toPx() / 2 + 9.dp.toPx()
            drawCircle(
                Brush.verticalGradient(
                    listOf(face.shade(0.25f), face.tint(0.12f)),
                    startY = center.y - wellR,
                    endY = center.y + wellR,
                ),
                wellR,
            )
        }
        Direction.entries.forEach { direction ->
            DialArrow(
                direction = direction,
                color = glyph,
                enabled = enabled,
                interaction = interactions.getValue(direction),
                modifier = Modifier
                    .align(direction.alignment)
                    .size(arrowHitSize),
            ) { onPress(direction.key) }
        }
        Key(
            onClick = { onPress(RemoteKey.Select) },
            shape = CircleShape,
            face = colors.primary,
            enabled = enabled,
            elevation = 6.dp,
            modifier = Modifier
                .align(Alignment.Center)
                .size(okKeySize),
        ) {
            Text(
                text = "OK",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = if (enabled) colors.onPrimary else colors.onSurfaceVariant,
            )
        }
    }
}

/** Arrow printed on the dial face: transparent hit area, drawn triangle only. */
@Composable
internal fun DialArrow(
    direction: Direction,
    color: Color,
    enabled: Boolean,
    interaction: MutableInteractionSource,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier = modifier
            .semantics { contentDescription = direction.label }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
            ) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .size(14.dp)
                .rotate(direction.rotation),
        ) {
            val path = Path().apply {
                moveTo(size.width / 2, size.height * 0.1f)
                lineTo(size.width, size.height * 0.85f)
                lineTo(0f, size.height * 0.85f)
                close()
            }
            drawPath(path, color)
        }
    }
}

/**
 * Raised key cap: lit-from-above gradient face, bevelled rim, drop shadow.
 * While held the gradient flips so the cap reads as pushed in. A double-tap
 * handler delays single taps until the double-tap window passes.
 */
@Composable
internal fun Key(
    onClick: () -> Unit,
    shape: Shape,
    face: Color,
    modifier: Modifier = Modifier,
    onDoubleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    elevation: Dp = 4.dp,
    haptic: Int = HapticFeedbackConstants.KEYBOARD_TAP,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val down = pressed
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 600f),
        label = "keyScale",
    )
    val lift by animateDpAsState(if (down) elevation / 4 else elevation, label = "keyLift")
    val cap = if (enabled) face else MaterialTheme.colorScheme.surfaceVariant
    val view = LocalView.current
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(elevation = lift, shape = shape)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (down) listOf(cap.shade(0.12f), cap.tint(0.04f))
                    else listOf(cap.tint(0.16f), cap.shade(0.08f)),
                ),
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = if (down) 0.05f else 0.3f), Color.Black.copy(alpha = 0.28f)),
                ),
                shape = shape,
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onDoubleClick = onDoubleClick?.let { { view.performHapticFeedback(haptic); it() } },
                onLongClick = onLongClick?.let { { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); it() } },
            ) {
                view.performHapticFeedback(haptic)
                onClick()
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Small caps printed into the casing, with a one-pixel lip below. */
@Composable
internal fun EngravedLabel(text: String) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            shadow = Shadow(
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

/** Slotted screw head, lit from the top-left. */
internal fun DrawScope.screw(center: Offset, radius: Float, angle: Float, base: Color) {
    drawCircle(
        Brush.radialGradient(
            listOf(base.tint(0.4f), base.shade(0.2f)),
            center = center - Offset(radius * 0.4f, radius * 0.4f),
            radius = radius * 1.6f,
        ),
        radius,
        center,
    )
    drawCircle(Color.Black.copy(alpha = 0.3f), radius, center, style = Stroke(0.8.dp.toPx()))
    rotate(angle, center) {
        drawLine(
            Color.Black.copy(alpha = 0.5f),
            center - Offset(radius * 0.65f, 0f),
            center + Offset(radius * 0.65f, 0f),
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
internal fun spacedEvenly(atLeast: Dp) = object : Arrangement.Vertical {
    override val spacing = atLeast

    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
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
 * [availableHeight], but no further than [MinFitScale]. The caller must be
 * scrollable: past the floor the remote stays taller than the space, so the
 * user's font setting keeps growing the text instead of being scaled away.
 */
internal fun Modifier.fitToHeight(availableHeight: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
    val scale = fitScale(placeable.height, availableHeight.roundToPx())
    val width = constraints.maxWidth
    layout(width, (placeable.height * scale).roundToInt()) {
        placeable.placeWithLayer((width - placeable.width) / 2, 0) {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0.5f, 0f)
        }
    }
}

/**
 * Smallest shrink the remote may take to fit; anything taller scrolls instead.
 * Text sizes are `sp`, so the scale races the system font setting: keep this
 * near 1, or at 1 to never shrink and always scroll.
 */
internal const val MinFitScale = 0.85f

/** 1 when [contentHeight] fits in [availableHeight]; otherwise the shrink factor, floored at [MinFitScale]. */
internal fun fitScale(contentHeight: Int, availableHeight: Int): Float =
    if (contentHeight <= availableHeight) 1f
    else (availableHeight.toFloat() / contentHeight).coerceAtLeast(MinFitScale)

internal fun Color.tint(fraction: Float) = lerp(this, Color.White, fraction)

internal fun Color.shade(fraction: Float) = lerp(this, Color.Black, fraction)
