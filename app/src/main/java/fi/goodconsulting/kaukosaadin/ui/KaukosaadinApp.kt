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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
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

private enum class Device(val label: String, val powerLabel: String, val os: String) {
    LgTv("LG G3", "LG TV", "WEBOS"),
    AppleTv("Apple TV", "APPLE TV", "TVOS"),
}

/** Arrows on the dial: glyph rotation, placement, and the quarter that tilts when held. */
private enum class Direction(val label: String, val rotation: Float, val alignment: Alignment, val wedgeStart: Float) {
    Up("Up", 0f, Alignment.TopCenter, -135f),
    Right("Right", 90f, Alignment.CenterEnd, -45f),
    Down("Down", 180f, Alignment.BottomCenter, 45f),
    Left("Left", 270f, Alignment.CenterStart, 135f),
}

/**
 * App root. UI lives in this `ui` package; device-network code will live in a
 * sibling `device` package (GOO-26 for LG, GOO-28 for Apple TV).
 */
@Composable
fun KaukosaadinApp() {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            RemoteScreen(contentPadding = innerPadding)
        }
    }
}

/**
 * The remote: power keys for both devices on top, a display, the source
 * selector, and navigation. Every control is local state only — commands are
 * echoed on the display, nothing is sent to a TV yet (GOO-26/GOO-28).
 */
@Composable
fun RemoteScreen(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    var activeDevice by remember { mutableStateOf(Device.LgTv) }
    var lgTvOn by remember { mutableStateOf(true) }
    var appleTvOn by remember { mutableStateOf(true) }
    var lastCommand by remember { mutableStateOf("") }
    // Bumped on every command so the TX lamp and annunciator blink once.
    var txCount by remember { mutableIntStateOf(0) }

    fun isOn(device: Device) = if (device == Device.LgTv) lgTvOn else appleTvOn

    fun send(command: String, device: Device = activeDevice) {
        lastCommand = "$command · ${device.label}"
        txCount++
    }

    fun togglePower(device: Device) {
        val on = !isOn(device)
        if (device == Device.LgTv) lgTvOn = on else appleTvOn = on
        send(if (on) "Power on" else "Standby", device)
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
                isOn = ::isOn,
                txFlash = { txFlash.value },
                onPower = ::togglePower,
            )

            VfdDisplay(
                device = activeDevice,
                isOn = isOn(activeDevice),
                status = if (lastCommand.isEmpty()) "NO COMMANDS YET"
                else "${lastCommand.uppercase(Locale.US)} · NOT SENT",
                txFlash = { txFlash.value },
            )

            SourceSelector(
                active = activeDevice,
                onSelect = { activeDevice = it },
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Dpad(
                    diameter = dialSize,
                    enabled = isOn(activeDevice),
                    onPress = { send(it) },
                )
                Key(
                    onClick = { send("Back") },
                    shape = RoundedCornerShape(50),
                    face = colors.secondaryContainer,
                    enabled = isOn(activeDevice),
                    elevation = 3.dp,
                    modifier = Modifier.size(width = dialSize - 24.dp, height = 44.dp),
                ) {
                    Text(
                        text = "BACK",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                        color = if (isOn(activeDevice)) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SpeakerGrille()
                    EngravedLabel("MODEL KS-01 · UNIVERSAL")
                }
            }
        }
    }
}

/**
 * Top deck: a red power key per device, each with its own standby LED, and
 * the TX lamp plus wordmark between them. Power never depends on the source
 * selector — either device can be switched from here at any time.
 */
@Composable
private fun PowerDeck(
    isOn: (Device) -> Boolean,
    txFlash: () -> Float,
    onPower: (Device) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        PowerKey(Device.LgTv, isOn(Device.LgTv)) { onPower(Device.LgTv) }
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
        PowerKey(Device.AppleTv, isOn(Device.AppleTv)) { onPower(Device.AppleTv) }
    }
}

@Composable
private fun PowerKey(device: Device, isOn: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Led(isOn)
        Key(
            onClick = onClick,
            shape = CircleShape,
            face = PowerRed,
            elevation = 6.dp,
            haptic = HapticFeedbackConstants.LONG_PRESS,
            modifier = Modifier
                .size(58.dp)
                .semantics {
                    contentDescription = "${device.label} power"
                    stateDescription = if (isOn) "On" else "Standby"
                },
        ) {
            PowerGlyph(OnPowerRed, Modifier.size(22.dp))
        }
        EngravedLabel(device.powerLabel)
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

/** Standby LED: glowing green when on, a dark unlit bead in standby. */
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
 * Amber VFD behind a smoked bezel: annunciators for target, power and TX on
 * top, the targeted device in large glowing type, and the command echo with
 * a blinking cursor.
 */
@Composable
private fun VfdDisplay(device: Device, isOn: Boolean, status: String, txFlash: () -> Float) {
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
                Annunciator("TV", lit = device == Device.LgTv)
                Annunciator("ATV", lit = device == Device.AppleTv)
                Spacer(Modifier.weight(1f))
                Annunciator("PWR", lit = isOn)
                Annunciator("STBY", lit = !isOn)
                Annunciator("TX", lit = txFlash() > 0.05f)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = device.label.uppercase(Locale.US),
                    // A device in standby dims to the secondary segment color.
                    style = vfdStyle(MaterialTheme.typography.headlineSmall, if (isOn) LcdText else LcdDim),
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = device.os,
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
 * Latching source keys, like the mode buttons on an old universal remote: the
 * targeted device's key stays pushed in with its lamp lit.
 */
@Composable
private fun SourceSelector(active: Device, onSelect: (Device) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EngravedLabel("SOURCE")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Device.entries.forEach { device ->
                val isActive = device == active
                Key(
                    onClick = { onSelect(device) },
                    shape = RoundedCornerShape(14.dp),
                    face = colors.secondaryContainer,
                    latched = isActive,
                    elevation = 4.dp,
                    role = Role.RadioButton,
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp)
                        .semantics { selected = isActive },
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        SourceLamp(isActive)
                        Text(
                            text = device.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceLamp(lit: Boolean) {
    val glow = MaterialTheme.colorScheme.primary
    val unlit = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.18f)
    val color by animateColorAsState(if (lit) glow else unlit, label = "sourceLamp")
    Canvas(Modifier.size(width = 22.dp, height = 4.dp)) {
        val corner = CornerRadius(size.height / 2)
        if (lit) {
            drawRoundRect(
                color = glow.copy(alpha = 0.3f),
                topLeft = Offset(-3.dp.toPx(), -3.dp.toPx()),
                size = Size(size.width + 6.dp.toPx(), size.height + 6.dp.toPx()),
                cornerRadius = CornerRadius(size.height),
            )
        }
        drawRoundRect(color, cornerRadius = corner)
    }
}

/**
 * One-piece navigation wheel: a knurled bezel, four arrows printed on the face,
 * and a raised OK key seated in a recessed well. The quarter under a held
 * arrow darkens as if the wheel tilts. When the target device is in standby,
 * the whole wheel greys out and refuses input.
 */
@Composable
private fun Dpad(
    diameter: Dp,
    enabled: Boolean,
    onPress: (String) -> Unit,
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
            ) { onPress(direction.label) }
        }
        Key(
            onClick = { onPress("OK") },
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
 * While held (or latched) the gradient flips so the cap reads as pushed in.
 */
@Composable
private fun Key(
    onClick: () -> Unit,
    shape: Shape,
    face: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    latched: Boolean = false,
    elevation: Dp = 4.dp,
    role: Role = Role.Button,
    haptic: Int = HapticFeedbackConstants.KEYBOARD_TAP,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val down = pressed || latched
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
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = role,
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
