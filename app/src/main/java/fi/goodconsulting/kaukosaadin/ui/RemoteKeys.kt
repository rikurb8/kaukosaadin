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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
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
import fi.goodconsulting.kaukosaadin.device.LgProtocol
import fi.goodconsulting.kaukosaadin.device.companion.CompanionSkipSupport
import fi.goodconsulting.kaukosaadin.device.companion.HidCommand
import fi.goodconsulting.kaukosaadin.device.companion.PressAction
import java.util.Locale

/** Remote keys and the command each device kind sends; LG has no Home, Play/Pause or volume key. */
internal enum class RemoteKey(
    val lg: LgProtocol.Action?,
    val hid: HidCommand,
) {
    Up(LgProtocol.Action.Up, HidCommand.Up),
    Down(LgProtocol.Action.Down, HidCommand.Down),
    Left(LgProtocol.Action.Left, HidCommand.Left),
    Right(LgProtocol.Action.Right, HidCommand.Right),
    Select(LgProtocol.Action.Select, HidCommand.Select),
    Back(LgProtocol.Action.Back, HidCommand.Menu),
    Home(null, HidCommand.Home),
    PlayPause(null, HidCommand.PlayPause),
    VolumeDown(null, HidCommand.VolumeDown),
    VolumeUp(null, HidCommand.VolumeUp),
}

/** Arrow rotation and placement on the directional pad. */
@Suppress("MagicNumber")
internal enum class Direction(
    val key: RemoteKey,
    val rotation: Float,
    val alignment: Alignment,
) {
    Up(RemoteKey.Up, 0f, Alignment.TopCenter),
    Right(RemoteKey.Right, 90f, Alignment.CenterEnd),
    Down(RemoteKey.Down, 180f, Alignment.BottomCenter),
    Left(RemoteKey.Left, 270f, Alignment.CenterStart),
    ;

    val label get() = key.name
}

/** Apple TV Back (Menu) and Home also take double tap and 1 s hold, like the Siri Remote. */
internal fun gestures(
    kind: DeviceKind,
    key: RemoteKey,
) = kind == DeviceKind.AppleTv && (key == RemoteKey.Back || key == RemoteKey.Home)

/** Navigation first; playback is a separate group and only exists for Apple TV. */
@Composable
internal fun RemoteKeys(
    dialSize: Dp,
    kind: DeviceKind,
    navigationEnabled: Boolean,
    onPress: (RemoteKey, PressAction) -> Unit,
    skipSupport: CompanionSkipSupport = CompanionSkipSupport(),
    onSkip: ((Double) -> Unit)? = null,
    fitToScreen: Boolean = false,
) {
    if (fitToScreen) {
        val keys = faceKeys(kind)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth > maxHeight) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FittedNavigation(Modifier.weight(1f), kind, keys, navigationEnabled, onPress)
                    if (kind == DeviceKind.AppleTv) {
                        PlaybackKeys(navigationEnabled, onPress, skipSupport, onSkip, Modifier.weight(1f), compact = true, stacked = true)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FittedNavigation(Modifier.weight(1f), kind, keys, navigationEnabled, onPress)
                    if (kind == DeviceKind.AppleTv) PlaybackKeys(navigationEnabled, onPress, skipSupport, onSkip, compact = true)
                }
            }
        }
        return
    }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("01 / NAVIGATE", Modifier.align(Alignment.Start), style = MaterialTheme.typography.labelSmall)
        Dpad(dialSize, navigationEnabled) { onPress(it, PressAction.Tap) }
        val keys = faceKeys(kind)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            keys.forEach { key ->
                PillKey(
                    key,
                    Modifier.weight(1f),
                    kind,
                    colors.surface,
                    colors.onSurface,
                    navigationEnabled,
                    onPress,
                )
            }
        }
        if (kind == DeviceKind.AppleTv) {
            PlaybackKeys(navigationEnabled, onPress, skipSupport, onSkip)
        }
    }
}

/** The keys a kind's face carries: every TV has Back, and Apple TV adds Home. */
private fun faceKeys(kind: DeviceKind): List<RemoteKey> =
    if (kind == DeviceKind.AppleTv) listOf(RemoteKey.Back, RemoteKey.Home) else listOf(RemoteKey.Back)

/** Only the pad flexes; the OK key stays at least 48dp and Back/Home keep their gestures. */
@Composable
private fun FittedNavigation(
    modifier: Modifier,
    kind: DeviceKind,
    keys: List<RemoteKey>,
    enabled: Boolean,
    onPress: (RemoteKey, PressAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Dpad(minOf(maxWidth, maxHeight, 280.dp).coerceAtLeast(168.dp), enabled) { onPress(it, PressAction.Tap) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            keys.forEach { key ->
                PillKey(key, Modifier.weight(1f), kind, colors.surface, colors.onSurface, enabled, onPress)
            }
        }
    }
}

/** Playback and volume keep their own visual group, away from directional navigation. */
@Composable
private fun PlaybackKeys(
    enabled: Boolean,
    onPress: (RemoteKey, PressAction) -> Unit,
    skipSupport: CompanionSkipSupport,
    onSkip: ((Double) -> Unit)?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    stacked: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
        if (!compact) Text("02 / PLAY & VOLUME", style = MaterialTheme.typography.labelSmall)
        if (stacked) {
            PillKey(
                RemoteKey.PlayPause,
                Modifier.fillMaxWidth(),
                DeviceKind.AppleTv,
                colors.primaryContainer,
                colors.onPrimaryContainer,
                enabled,
                onPress,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!stacked) {
                PillKey(
                    RemoteKey.PlayPause,
                    Modifier.weight(1.3f),
                    DeviceKind.AppleTv,
                    colors.primaryContainer,
                    colors.onPrimaryContainer,
                    enabled,
                    onPress,
                )
            }
            listOf(RemoteKey.VolumeDown, RemoteKey.VolumeUp).forEach { key ->
                PillKey(key, Modifier.weight(1f), DeviceKind.AppleTv, colors.surface, colors.onSurface, enabled, onPress)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(
                onClick = { onSkip?.invoke(-10.0) },
                enabled = enabled && onSkip != null && skipSupport.backward,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Skip backward 10 seconds" },
            ) { Text(if (compact) "−10 s" else "−10 seconds") }
            TextButton(
                onClick = { onSkip?.invoke(10.0) },
                enabled = enabled && onSkip != null && skipSupport.forward,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Skip forward 10 seconds" },
            ) { Text(if (compact) "+10 s" else "+10 seconds") }
        }
    }
}

/** One Back/Home/Play-Pause pill: glyph for Play/Pause, engraved label otherwise. */
@Composable
private fun PillKey(
    key: RemoteKey,
    modifier: Modifier,
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
        shape = RoundedCornerShape(18.dp),
        face = face,
        enabled = enabled,
        elevation = 3.dp,
        modifier =
            modifier
                .heightIn(min = 60.dp)
                .padding(bottom = 4.dp)
                .then(
                    if (key == RemoteKey.PlayPause) {
                        Modifier.semantics { contentDescription = "Play/Pause" }
                    } else {
                        Modifier
                    },
                ),
    ) {
        if (key == RemoteKey.PlayPause) {
            PlayPauseGlyph(
                if (enabled) legend else MaterialTheme.colorScheme.onSurfaceVariant,
                Modifier.size(width = 32.dp, height = 18.dp),
            )
        } else {
            Text(
                text = key.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
                color = if (enabled) legend else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
            )
        }
    }
}

/** Engraved label on a pill: the enum name, except volume's VOL -/+ signs. */
private val RemoteKey.label: String
    get() =
        when (this) {
            RemoteKey.VolumeDown -> "Vol −"
            RemoteKey.VolumeUp -> "Vol +"
            else -> name.uppercase(Locale.US)
        }

/**
 * A square directional pad with a contrasting OK key. Arrow and OK hit areas never overlap.
 * Until the device is ready, the whole pad greys out and refuses input.
 */
@Composable
internal fun Dpad(
    diameter: Dp,
    enabled: Boolean,
    onPress: (RemoteKey) -> Unit,
) {
    val okKeySize = diameter / 3 - 8.dp
    val arrowHitSize = diameter / 3
    val colors = MaterialTheme.colorScheme
    val face = if (enabled) colors.primary else colors.surfaceVariant
    val glyph = if (enabled) colors.onPrimary else colors.onSurfaceVariant
    val shape = RoundedCornerShape(32.dp)
    val interactions = remember { Direction.entries.associateWith { MutableInteractionSource() } }
    val held = Direction.entries.filter { interactions.getValue(it).collectIsPressedAsState().value }
    Box(
        modifier =
            Modifier
                .size(diameter)
                .alpha(if (enabled) 1f else 0.5f)
                .clip(shape)
                .background(face)
                .border(2.dp, colors.outline, shape),
    ) {
        Direction.entries.forEach { direction ->
            DialArrow(
                direction = direction,
                color = glyph,
                enabled = enabled,
                interaction = interactions.getValue(direction),
                modifier =
                    Modifier
                        .align(direction.alignment)
                        .size(arrowHitSize)
                        .background(
                            if (direction in held) colors.onPrimary.copy(alpha = 0.2f) else Color.Transparent,
                            RoundedCornerShape(24.dp),
                        ),
            ) { onPress(direction.key) }
        }
        Key(
            onClick = { onPress(RemoteKey.Select) },
            shape = CircleShape,
            face = colors.secondaryContainer,
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
                color = if (enabled) colors.onSecondaryContainer else colors.onSurfaceVariant,
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
 * Flat key cap with an ink outline and a hard offset shadow.
 * While held the cap sinks toward its shadow. A double-tap
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

/** Cap chrome: press animation, flat face, ink outline and tap gestures. */
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
    val ink = MaterialTheme.colorScheme.outline
    Box(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.drawBehind {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    translate(left = lift.toPx(), top = lift.toPx()) {
                        drawOutline(outline, ink)
                    }
                }.clip(shape)
                .background(if (down) cap.shade(0.08f) else cap)
                .border(2.dp, ink, shape)
                .combinedClickable(
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
