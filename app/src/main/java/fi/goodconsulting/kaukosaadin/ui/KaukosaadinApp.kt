package fi.goodconsulting.kaukosaadin.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.goodconsulting.kaukosaadin.device.LgClient
import fi.goodconsulting.kaukosaadin.device.LgProtocol
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import fi.goodconsulting.kaukosaadin.device.companion.CompanionDiscovery
import fi.goodconsulting.kaukosaadin.device.companion.HidCommand
import fi.goodconsulting.kaukosaadin.device.companion.PressAction
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Braun-dieter-rams palette: cream casing, charcoal keys, one orange accent.
 * Keys and cards borrow their colors from `secondaryContainer`/`surface`.
 */
private val LightColors = lightColorScheme(
    background = Color(0xFFE9E2D0),
    onBackground = Color(0xFF2A261D),
    surface = Color(0xFFF5F0E1),
    onSurface = Color(0xFF2A261D),
    surfaceVariant = Color(0xFFD8D0BB),
    onSurfaceVariant = Color(0xFF6E6753),
    outline = Color(0xFFB9AE93),
    primary = Color(0xFFE3600F),
    onPrimary = Color(0xFFFFF7EC),
    primaryContainer = Color(0xFFF7DFC8),
    onPrimaryContainer = Color(0xFF4A2000),
    secondaryContainer = Color(0xFFDCD3BD),
    onSecondaryContainer = Color(0xFF332E22),
)

private val DarkColors = darkColorScheme(
    background = Color(0xFF1C1914),
    onBackground = Color(0xFFEDE6D6),
    surface = Color(0xFF262219),
    onSurface = Color(0xFFEDE6D6),
    surfaceVariant = Color(0xFF322C21),
    onSurfaceVariant = Color(0xFFA39A84),
    outline = Color(0xFF4E463A),
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF331300),
    primaryContainer = Color(0xFF4A2A12),
    onPrimaryContainer = Color(0xFFFFC08F),
    secondaryContainer = Color(0xFF4A4232),
    onSecondaryContainer = Color(0xFFE4DCC6),
)

/** Amber VFD panel — fixed colors, the display glows the same in both themes. */
private val LcdBackdrop = Color(0xFF1B160D)
private val LcdBezelTop = Color(0xFF0A0805)
private val LcdBezelBottom = Color(0xFF2E281C)
private val LcdText = Color(0xFFFFB84D)
private val LcdDim = Color(0xFFB08A55)
private val LcdGhost = Color(0xFF3A301F)

/** Printed-plastic hardware colors, identical in both themes. */
private val PowerRed = Color(0xFFC8372B)
private val OnPowerRed = Color(0xFFFFEDE6)
private val LedOn = Color(0xFF8FE04F)
private val LedOff = Color(0xFF4A4436)
private val TxLens = Color(0xFF1E1112)
private val TxGlow = Color(0xFFFF3B2F)

/**
 * Dial diameter bounds. On a phone the dial grows with the screen width; the
 * OK key and arrow hit areas are fixed fractions of it.
 */
private val MinDialSize = 216.dp
private val MaxDialSize = 264.dp

/**
 * Wider than this (tablets, unfolded foldables) the remote stays phone-sized
 * as a framed casing on a backdrop; narrower, the casing is the whole screen.
 */
private val FramedMinWidth = 480.dp
private val FramedMaxWidth = 420.dp

/** The saved device the remote drives; each keeps its own pairing and status. */
private enum class Target(val label: String, val annunciator: String, val platform: String) {
    Lg("LG TV", "TV", "WEBOS"),
    AppleTv("Apple TV", "ATV", "TVOS"),
}

/** Remote keys and the command each target sends; LG has no Home or Play/Pause key. */
private enum class RemoteKey(val lg: LgProtocol.Action?, val hid: HidCommand) {
    Up(LgProtocol.Action.Up, HidCommand.Up),
    Down(LgProtocol.Action.Down, HidCommand.Down),
    Left(LgProtocol.Action.Left, HidCommand.Left),
    Right(LgProtocol.Action.Right, HidCommand.Right),
    Select(LgProtocol.Action.Select, HidCommand.Select),
    Back(LgProtocol.Action.Back, HidCommand.Menu),
    Home(null, HidCommand.Home),
    PlayPause(null, HidCommand.PlayPause),
}

/** Arrows on the dial: glyph rotation, placement, and the quarter that tilts when held. */
private enum class Direction(val key: RemoteKey, val rotation: Float, val alignment: Alignment, val wedgeStart: Float) {
    Up(RemoteKey.Up, 0f, Alignment.TopCenter, -135f),
    Right(RemoteKey.Right, 90f, Alignment.CenterEnd, -45f),
    Down(RemoteKey.Down, 180f, Alignment.BottomCenter, 45f),
    Left(RemoteKey.Left, 270f, Alignment.CenterStart, 135f),
    ;
    val label get() = key.name
}

/**
 * One LG client and one Apple TV client share setup, pairing and readiness across
 * the screens; the remote drives whichever saved device is the selected target.
 */
@Composable
fun KaukosaadinApp() {
    val context = LocalContext.current.applicationContext
    val client = remember { LgClient(context) }
    val apple = remember { CompanionClient(context) }
    val discovery = remember { CompanionDiscovery(context) }
    val scope = rememberCoroutineScope()
    val status by client.status.collectAsState()
    val ready by client.ready.collectAsState()
    val appleStatus by apple.status.collectAsState()
    val applePaired by apple.paired.collectAsState()
    var lgSettings by remember { mutableStateOf(false) }
    var appleSettings by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(Target.Lg) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LgClient.Result?>(null) }
    var appleResult by remember { mutableStateOf<CompanionClient.Result?>(null) }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { try { block() } finally { busy = false } }
    }
    val lgSaved = client.host.isNotEmpty()
    // Fall back to whichever device is actually saved, e.g. after removing the other.
    val target = when {
        selected == Target.Lg && !lgSaved && applePaired -> Target.AppleTv
        selected == Target.AppleTv && !applePaired && lgSaved -> Target.Lg
        else -> selected
    }
    fun openSettings(to: Target) {
        selected = to
        if (to == Target.Lg) lgSettings = true else appleSettings = true
    }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
        LgPinDialog(client)
        AppleTvPinDialog(apple)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            if (lgSettings) {
                LgConnectionScreen(innerPadding, client) { result = null; lgSettings = false }
            } else if (appleSettings) {
                AppleTvSetupScreen(innerPadding, apple, discovery) { appleResult = null; appleSettings = false }
            } else if (!lgSaved && !applePaired) {
                EmptyRemoteScreen(innerPadding, onAdd = ::openSettings)
            } else if (target == Target.Lg) {
                RemoteScreen(
                    contentPadding = innerPadding,
                    target = target,
                    tvName = client.name,
                    ready = ready,
                    busy = busy,
                    wakeEnabled = client.mac.isNotEmpty() && client.broadcast.isNotEmpty() && client.fingerprint.isNotEmpty(),
                    status = (if (busy) status else result ?: status).message,
                    onTarget = { if (it == Target.Lg || applePaired) selected = it else openSettings(it) },
                    onConnect = {
                        if (client.host.isEmpty() || client.fingerprint.isEmpty()) lgSettings = true
                        else run { result = client.connect() }
                    },
                    onSettings = { lgSettings = true },
                    onWake = { run { result = client.send(LgProtocol.Action.Wake) } },
                    onKey = { key, _ -> key.lg?.let { action -> run { result = client.send(action) } } },
                )
            } else {
                RemoteScreen(
                    contentPadding = innerPadding,
                    target = target,
                    tvName = apple.name,
                    ready = applePaired,
                    busy = busy,
                    wakeEnabled = false,
                    status = (if (busy) appleStatus else appleResult ?: appleStatus).message,
                    onTarget = { if (it == Target.AppleTv || lgSaved) selected = it else openSettings(it) },
                    onConnect = null,
                    onSettings = { appleSettings = true },
                    onWake = {},
                    onKey = { key, action -> run { appleResult = apple.press(key.hid, action) } },
                )
            }
        }
    }
}

@Composable
private fun EmptyRemoteScreen(contentPadding: PaddingValues, onAdd: (Target) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        EngravedLabel("KAUKOSÄÄDIN")
        Text("No TVs added", style = MaterialTheme.typography.headlineSmall)
        Text("Add your TV to start using the remote.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { onAdd(Target.Lg) }) { Text("Add LG TV") }
        Button(onClick = { onAdd(Target.AppleTv) }) { Text("Add Apple TV") }
        Text("Supports LG webOS TVs and Apple TV on your Wi-Fi", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Only actual saved devices appear; controls require verified registration (LG) or
 * a saved pairing (Apple TV). [onConnect] is null for targets that verify per press.
 */
@Composable
private fun RemoteScreen(
    contentPadding: PaddingValues,
    target: Target,
    tvName: String,
    ready: Boolean,
    busy: Boolean,
    wakeEnabled: Boolean,
    status: String,
    onTarget: (Target) -> Unit,
    onConnect: (() -> Unit)?,
    onSettings: () -> Unit,
    onWake: () -> Unit,
    onKey: (RemoteKey, PressAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var txCount by remember { mutableIntStateOf(0) }
    val navigationEnabled = ready && !busy
    // Apple TV Back (Menu) and Home also take double tap and 1 s hold, like the Siri Remote.
    fun gestures(key: RemoteKey) = target == Target.AppleTv && (key == RemoteKey.Back || key == RemoteKey.Home)

    fun send(key: RemoteKey, action: PressAction = PressAction.Tap) {
        txCount++
        onKey(key, action)
    }

    val txFlash = remember { Animatable(0f) }
    LaunchedEffect(txCount) {
        if (txCount > 0) {
            txFlash.snapTo(1f)
            txFlash.animateTo(0f, tween(durationMillis = 380, easing = LinearOutSlowInEasing))
        }
    }

    val colors = MaterialTheme.colorScheme
    val casingBrush = Brush.verticalGradient(listOf(colors.surface.tint(0.05f), colors.surface.shade(0.06f)))
    val casingShape = RoundedCornerShape(32.dp)
    val screwColor = colors.outline
    BoxWithConstraints(modifier.fillMaxSize()) {
        // On a phone the phone *is* the remote: the casing fills the screen,
        // behind the system bars too. Wide screens keep a phone-sized casing
        // on the desk backdrop instead of stretching the keys across it.
        val framed = maxWidth >= FramedMinWidth
        val casingWidth = if (framed) FramedMaxWidth else maxWidth
        val dialSize = (casingWidth * 0.62f).coerceIn(MinDialSize, MaxDialSize)
        val visibleHeight = maxHeight - contentPadding.calculateTopPadding() - contentPadding.calculateBottomPadding()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (framed) Modifier else Modifier.background(casingBrush))
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .then(if (framed) Modifier.padding(horizontal = 16.dp, vertical = 8.dp) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = if (framed) {
                    Modifier
                        .width(FramedMaxWidth)
                        .shadow(elevation = 16.dp, shape = casingShape)
                        .clip(casingShape)
                        .background(casingBrush)
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), colors.outline)),
                            shape = casingShape,
                        )
                        .drawBehind {
                            val inset = 15.dp.toPx()
                            val radius = 4.5.dp.toPx()
                            screw(Offset(inset, inset), radius, 35f, screwColor)
                            screw(Offset(size.width - inset, inset), radius, -20f, screwColor)
                            screw(Offset(inset, size.height - inset), radius, 80f, screwColor)
                            screw(Offset(size.width - inset, size.height - inset), radius, 10f, screwColor)
                        }
                } else {
                    // Fill the visible height so spare room spreads between
                    // sections rather than pooling under the grille.
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = visibleHeight)
                }.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = if (framed) Arrangement.spacedBy(18.dp) else spacedEvenly(atLeast = 18.dp),
            ) {
            PowerDeck(
                ready = ready,
                wakeEnabled = wakeEnabled && !busy,
                txFlash = { txFlash.value },
                onWake = {
                    txCount++
                    onWake()
                },
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Target.entries.forEachIndexed { index, entry ->
                        SegmentedButton(
                            selected = entry == target,
                            onClick = { onTarget(entry) },
                            enabled = !busy,
                            shape = SegmentedButtonDefaults.itemShape(index, Target.entries.size),
                        ) { Text(entry.label) }
                    }
                }
                VfdDisplay(
                    target = target,
                    tvName = tvName,
                    ready = ready,
                    status = status.uppercase(Locale.US),
                    txFlash = { txFlash.value },
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Row {
                    if (onConnect != null) {
                        TextButton(onClick = onConnect, enabled = !busy) {
                            Text(if (ready) "Reconnect TV" else "Connect TV")
                        }
                        TextButton(onClick = onSettings, enabled = !busy) { Text("TV settings") }
                    } else {
                        TextButton(onClick = onSettings, enabled = !busy) { Text("Apple TV settings") }
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Dpad(
                    diameter = dialSize,
                    enabled = navigationEnabled,
                    onPress = { send(it) },
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
                                onClick = { send(key) },
                                onDoubleClick = if (gestures(key)) ({ send(key, PressAction.DoubleTap) }) else null,
                                onLongClick = if (gestures(key)) ({ send(key, PressAction.Hold) }) else null,
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
                if (target == Target.AppleTv) {
                    Text("Back/Home: double tap = double press · hold = 1 s hold", style = MaterialTheme.typography.bodySmall)
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (target == Target.Lg) "Wake only · TV power is not monitored" else "No Apple TV wake · power is not monitored",
                    style = MaterialTheme.typography.bodySmall,
                )
                SpeakerGrille()
                    EngravedLabel("MODEL KS-01 · UNIVERSAL")
                }
            }
        }
    }
}

/** Wake button for the saved TV; the LED indicates registration, not power. */
@Composable
private fun PowerDeck(
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
private fun PowerKey(ready: Boolean, enabled: Boolean, onClick: () -> Unit) {
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
private fun PowerGlyph(color: Color, modifier: Modifier = Modifier) {
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
private fun PlayPauseGlyph(color: Color, modifier: Modifier = Modifier) {
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
private fun Led(isOn: Boolean) {
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
private fun TxLamp(flash: () -> Float) {
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
private fun VfdDisplay(target: Target, tvName: String, ready: Boolean, status: String, txFlash: () -> Float) {
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
private fun Annunciator(text: String, lit: Boolean) {
    Text(
        text = text,
        style = if (lit) vfdStyle(MaterialTheme.typography.labelSmall, LcdText)
        else MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, color = LcdGhost),
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun BlinkingCursor() {
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
private fun vfdStyle(base: TextStyle, color: Color) = base.copy(
    fontFamily = FontFamily.Monospace,
    color = color,
    shadow = Shadow(color = color.copy(alpha = 0.75f), blurRadius = 14f),
)

/** Scanlines and a glass glare over the display content. */
private fun Modifier.vfdGlass() = drawWithContent {
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
private fun Dpad(
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
private fun DialArrow(
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
private fun Key(
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
private fun EngravedLabel(text: String) {
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
private fun SpeakerGrille() {
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
private fun DrawScope.screw(center: Offset, radius: Float, angle: Float, base: Color) {
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
 * [atLeast]. Unlike SpaceBetween it keeps a real gap when content overflows
 * and the column scrolls.
 */
private fun spacedEvenly(atLeast: Dp) = object : Arrangement.Vertical {
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

private fun Color.tint(fraction: Float) = lerp(this, Color.White, fraction)

private fun Color.shade(fraction: Float) = lerp(this, Color.Black, fraction)
