package fi.goodconsulting.kaukosaadin.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.companion.PressAction
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Apple TV Back (Menu) and Home also take double tap and 1 s hold, like the Siri Remote. */
internal fun gestures(
    kind: DeviceKind,
    key: RemoteKey,
) = kind == DeviceKind.AppleTv && (key == RemoteKey.Back || key == RemoteKey.Home)

/** Wheel plus Back/Home/Play-Pause keys; shared by every layout. */
@Composable
internal fun RemoteKeys(
    dialSize: Dp,
    kind: DeviceKind,
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
        // Siri Remote order: Back and Home side by side, then Play/Pause and volume below.
        // Volume shares the Play/Pause row so the casing keeps the footprint it had before
        // the keys existed; a third row pushed the grille and engraving off a phone screen.
        val pillRows =
            if (kind == DeviceKind.AppleTv) {
                listOf(
                    listOf(RemoteKey.Back, RemoteKey.Home),
                    listOf(RemoteKey.PlayPause, RemoteKey.VolumeDown, RemoteKey.VolumeUp),
                )
            } else {
                listOf(listOf(RemoteKey.Back))
            }
        val legend = if (navigationEnabled) colors.onSecondaryContainer else colors.onSurfaceVariant
        pillRows.forEach { row ->
            // Each row sizes its own pills: the Play/Pause row has three, the others two or one.
            val pillWidth = (dialSize - 24.dp - 12.dp * (row.size - 1)) / row.size
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    PillKey(key, pillWidth, kind, colors.secondaryContainer, legend, navigationEnabled, onPress)
                }
            }
        }
        // Keep the Play/Pause row's footprint: fitToHeight must not resize the
        // entire remote when switching to a device kind with fewer keys.
        if (kind == DeviceKind.Lg) Spacer(Modifier.height(44.dp))
    }
}

/** One Back/Home/Play-Pause pill: glyph for Play/Pause, engraved label otherwise. */
@Composable
private fun PillKey(
    key: RemoteKey,
    width: Dp,
    kind: DeviceKind,
    face: Color,
    legend: Color,
    enabled: Boolean,
    onPress: (RemoteKey, PressAction) -> Unit,
) {
    Key(
        onClick = { onPress(key, PressAction.Tap) },
        onDoubleClick = if (gestures(kind, key)) ({ onPress(key, PressAction.DoubleTap) }) else null,
        onLongClick = if (gestures(kind, key)) ({ onPress(key, PressAction.Hold) }) else null,
        shape = RoundedCornerShape(50),
        face = face,
        enabled = enabled,
        elevation = 3.dp,
        modifier =
            Modifier
                .size(width = width, height = 44.dp)
                .then(
                    if (key == RemoteKey.PlayPause) {
                        Modifier.semantics { contentDescription = "Play/Pause" }
                    } else {
                        Modifier
                    },
                ),
    ) {
        if (key == RemoteKey.PlayPause) {
            PlayPauseGlyph(legend, Modifier.size(width = 30.dp, height = 14.dp))
        } else {
            Text(
                text = key.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
                color = legend,
            )
        }
    }
}

/** Engraved label on a pill: the enum name, except volume's VOL -/+ signs. */
private val RemoteKey.label: String
    get() =
        when (this) {
            RemoteKey.VolumeDown -> "VOL -"
            RemoteKey.VolumeUp -> "VOL +"
            else -> name.uppercase(Locale.US)
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
        modifier =
            Modifier
                .size(diameter)
                .alpha(if (enabled) 1f else 0.5f)
                .shadow(elevation = 10.dp, shape = CircleShape),
    ) {
        DpadFace(face, held, okKeySize, Modifier.matchParentSize())
        Direction.entries.forEach { direction ->
            DialArrow(
                direction = direction,
                color = glyph,
                enabled = enabled,
                interaction = interactions.getValue(direction),
                modifier =
                    Modifier
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
            modifier =
                Modifier
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

/** Bezel knurling, dial face, held-arrow shading and the recessed OK well. */
@Composable
private fun DpadFace(
    face: Color,
    held: List<Direction>,
    okKeySize: Dp,
    modifier: Modifier,
) {
    Canvas(modifier) {
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
        modifier =
            modifier
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
            val path =
                Path().apply {
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
    KeySurface(
        onClick = onClick,
        shape = shape,
        cap = cap,
        modifier = modifier,
        onDoubleClick = onDoubleClick,
        onLongClick = onLongClick,
        enabled = enabled,
        down = down,
        scale = scale,
        lift = lift,
        haptic = haptic,
        interaction = interaction,
        content = content,
    )
}

/** Cap chrome: press animation, lit gradient face, bevelled rim, tap gestures. */
@Composable
private fun KeySurface(
    onClick: () -> Unit,
    shape: Shape,
    cap: Color,
    modifier: Modifier,
    onDoubleClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    enabled: Boolean,
    down: Boolean,
    scale: Float,
    lift: Dp,
    haptic: Int,
    interaction: MutableInteractionSource,
    content: @Composable BoxScope.() -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.shadow(elevation = lift, shape = shape)
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        if (down) {
                            listOf(cap.shade(0.12f), cap.tint(0.04f))
                        } else {
                            listOf(cap.tint(0.16f), cap.shade(0.08f))
                        },
                    ),
                ).border(
                    width = 1.dp,
                    brush =
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = if (down) 0.05f else 0.3f), Color.Black.copy(alpha = 0.28f)),
                        ),
                    shape = shape,
                ).combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onDoubleClick =
                        onDoubleClick?.let {
                            {
                                view.performHapticFeedback(haptic)
                                it()
                            }
                        },
                    onLongClick =
                        onLongClick?.let {
                            {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                it()
                            }
                        },
                ) {
                    view.performHapticFeedback(haptic)
                    onClick()
                },
        contentAlignment = Alignment.Center,
        content = content,
    )
}
