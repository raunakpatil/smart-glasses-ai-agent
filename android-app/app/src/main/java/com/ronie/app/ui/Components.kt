package com.ronie.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

val CardShape = RoundedCornerShape(28.dp)
val PillShape = RoundedCornerShape(999.dp)

/** Where a card's glow comes from. */
enum class GlowFrom { Top, Bottom, Left, Right, Center, TopRight, BottomLeft }

/**
 * The signature card: near-black, with a soft coloured glow bleeding in from one side (references: the pink
 * "Reminder" and violet "Expiratory volume" cards). [strength] 0…1 dims the glow for quieter cards. Glow colour and
 * strength glide when they change, and the glow always drifts slowly ([driftMs] per loop, each card on its own phase). Cards rise into
 * place on their screen and sink a little when pressed.
 */
@Composable
fun GlowCard(
    modifier: Modifier = Modifier,
    glow: Glow? = null,
    from: GlowFrom = GlowFrom.Top,
    strength: Float = 1f,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    padding: Dp = 20.dp,
    driftMs: Int = 10000,
    orbit: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ink = Ronie.ink
    val core by animateColorAsState(glow?.core ?: ink.card, tween(700, easing = EaseOutSoft), label = "core")
    val edge by animateColorAsState(glow?.edge ?: ink.card, tween(700, easing = EaseOutSoft), label = "edge")
    val power by animateFloatAsState(if (glow == null) 0f else strength, tween(600, easing = EaseOutSoft), label = "power")
    val border by animateColorAsState(if (selected) ink.onCard else ink.line.copy(alpha = 0.6f), tween(300), label = "border")
    val phase = remember { (Math.random() * 2 * PI).toFloat() } // so neighbouring cards don't move in step
    val drift = rememberInfiniteTransition(label = "drift")
        .animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(driftMs, easing = LinearEasing)), label = "a").value + phase
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Column(
        modifier
            .reveal()
            .then(if (onClick != null) Modifier.pressScale(source, 0.975f) else Modifier)
            .clip(CardShape)
            .background(ink.card)
            .drawBehind {
                if (power <= 0.01f) return@drawBehind
                val w = size.width
                val h = size.height
                var center = when (from) {
                    GlowFrom.Top -> Offset(w / 2, -h * 0.15f)
                    GlowFrom.Bottom -> Offset(w / 2, h * 1.15f)
                    GlowFrom.Left -> Offset(-w * 0.15f, h / 2)
                    GlowFrom.Right -> Offset(w * 1.15f, h / 2)
                    GlowFrom.Center -> Offset(w / 2, h / 2)
                    GlowFrom.TopRight -> Offset(w * 1.0f, 0f)
                    GlowFrom.BottomLeft -> Offset(0f, h)
                }
                if (orbit) {
                    // a slow lap round the card, close to its edges, lingering near the corners
                    val a = drift
                    val cx = cos(a).let { kotlin.math.sign(it) * kotlin.math.abs(it).let { m -> Math.pow(m.toDouble(), 0.55).toFloat() } }
                    val cy = sin(a).let { kotlin.math.sign(it) * kotlin.math.abs(it).let { m -> Math.pow(m.toDouble(), 0.55).toFloat() } }
                    center = Offset(w / 2 + cx * w * 0.42f, h / 2 + cy * h * 0.42f)
                } else center += Offset(cos(drift) * w * 0.2f, sin(drift * 2) * h * 0.14f)
                val breathe = 1f + (if (orbit) 0.04f else 0.1f) * sin(drift * 3)
                val r = max(w, h) * (if (from == GlowFrom.Center) 0.62f else 0.95f) * breathe
                drawRect(
                    Brush.radialGradient(
                        0f to core.copy(alpha = 0.95f * power),
                        0.38f to edge.copy(alpha = 0.85f * power),
                        1f to Color.Transparent,
                        center = center, radius = r,
                    ),
                )
            }
            .border(if (selected) 1.5.dp else 1.dp, border, CardShape)
            .then(if (onClick != null) Modifier.clickable(source, indication = null) { haptics.tick(); onClick() } else Modifier)
            .padding(padding),
        content = content,
    )
}

// ---------- dot-matrix numbers ----------

private val GLYPHS: Map<Char, List<String>> = mapOf(
    '0' to listOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
    '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
    '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
    '3' to listOf(".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###."),
    '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
    '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
    '6' to listOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
    '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
    '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
    '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
    '%' to listOf("##..#", "##..#", "...#.", "..#..", ".#...", "#..##", "#..##"),
    '.' to listOf(".", ".", ".", ".", ".", ".", "#"),
    ':' to listOf(".", ".", "#", ".", "#", ".", "."),
    '-' to listOf(".....", ".....", ".....", "#####", ".....", ".....", "....."),
    ' ' to listOf("..", "..", "..", "..", "..", "..", ".."),
)

/** A number drawn as round dots, like the references' "90 mg/dL". Unknown characters are skipped. */
@Composable
fun DotNumber(text: String, modifier: Modifier = Modifier, dot: Dp = 4.dp, color: Color = Ronie.ink.onCard) {
    // Whole numbers count up when they first appear or change; anything else is drawn as is.
    val target = text.toIntOrNull()
    val shown = remember { Animatable(0f) }
    LaunchedEffect(target) { if (target != null) shown.animateTo(target.toFloat(), tween(900, easing = EaseOutSoft)) }
    val value = if (target != null) shown.value.toInt().toString() else text
    val glyphs = value.mapNotNull { GLYPHS[it] }
    val cols = glyphs.sumOf { it[0].length } + max(0, glyphs.size - 1)
    val pitch = dot * 1.55f
    Canvas(modifier.size(width = pitch * cols, height = pitch * 7)) {
        val p = pitch.toPx()
        val rad = dot.toPx() / 2
        var x0 = 0f
        glyphs.forEach { g ->
            g.forEachIndexed { row, line ->
                line.forEachIndexed { col, c ->
                    val center = Offset(x0 + col * p + p / 2, row * p + p / 2)
                    if (c == '#') drawCircle(color, rad, center) else drawCircle(color.copy(alpha = 0.07f), rad * 0.6f, center)
                }
            }
            x0 += (g[0].length + 1) * p
        }
    }
}

// ---------- buttons ----------

/** The references' "Start tracking (+)" pill: a label, then a white circle with an icon. */
@Composable
fun PillAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.AutoMirrored.Outlined.ArrowForward,
    enabled: Boolean = true,
    loading: Boolean = false,
    light: Boolean = false,
) {
    val ink = Ronie.ink
    val bg = if (light) ink.onCard else ink.raised.copy(alpha = 0.92f)
    val fg = if (light) Color(0xFF0A0A0B) else ink.onCard
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Surface(
        onClick = { haptics.click(); onClick() }, enabled = enabled && !loading, shape = PillShape, color = bg,
        border = if (light) null else BorderStroke(1.dp, ink.line), interactionSource = source,
        modifier = modifier.pressScale(source, 0.96f).heightIn(min = 56.dp),
    ) {
        Row(
            Modifier.padding(start = 22.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // weight(1f) pushes the circle to the right edge when the pill is wider than its text
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.weight(1f))
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (light) Color(0xFF0A0A0B) else ink.onCard.copy(alpha = if (enabled) 1f else 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = if (light) ink.onCard else Color(0xFF0A0A0B))
                else Icon(icon, contentDescription = null, tint = if (light) ink.onCard else Color(0xFF0A0A0B), modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** A plain secondary pill: outlined, text only. */
@Composable
fun GhostPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val ink = Ronie.ink
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Surface(onClick = { haptics.tick(); onClick() }, enabled = enabled, shape = PillShape, color = Color.Transparent, border = BorderStroke(1.dp, ink.line),
        interactionSource = source, modifier = modifier.pressScale(source, 0.96f).heightIn(min = 48.dp)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = ink.onCard.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = ink.onCard.copy(alpha = if (enabled) 1f else 0.4f))
        }
    }
}

/** A round icon button: dark circle, or white when [selected]. */
@Composable
fun RoundIcon(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, size: Dp = 48.dp) {
    val ink = Ronie.ink
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Surface(
        onClick = { haptics.tick(); onClick() }, shape = CircleShape, color = if (selected) ink.onCard else ink.raised,
        border = if (selected) null else BorderStroke(1.dp, ink.line), interactionSource = source,
        modifier = modifier.pressScale(source, 0.9f).size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = if (selected) Color(0xFF0A0A0B) else ink.onCard, modifier = Modifier.size(size * 0.42f))
        }
    }
}

/** A small round chip: white when selected. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ink = Ronie.ink
    val fill by animateColorAsState(if (selected) ink.onCard else Color.Transparent, tween(220), label = "chip")
    val fg by animateColorAsState(if (selected) Color(0xFF0A0A0B) else ink.onCard, tween(220), label = "chipText")
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Surface(onClick = { haptics.tick(); onClick() }, shape = PillShape, color = fill, interactionSource = source,
        border = BorderStroke(1.dp, if (selected) Color.Transparent else ink.line), modifier = modifier.pressScale(source, 0.94f).heightIn(min = 40.dp)) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        }
    }
}

// ---------- text, rows, fields ----------

/** A screen's big title with an optional line under it. */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = Ronie.ink.onCard)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Ronie.ink.onCard2)
    }
}

/** A mono label above a group, with an optional action on the right. */
@Composable
fun SectionLabel(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        MonoLabel(text)
        if (action != null && onAction != null) Text(action, style = MaterialTheme.typography.labelSmall, color = Ronie.ink.onCard,
            modifier = Modifier.clip(PillShape).clickable(onClick = onAction).padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

/** An icon in a soft circle, for list rows. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = Ronie.ink.onCard, bg: Color = Ronie.ink.raised, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

/** A setting row: badge, title + line, and something on the right (a switch, a pill). */
@Composable
fun SettingRow(icon: ImageVector, title: String, detail: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick) else Modifier).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ronie.ink.onCard)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Ronie.ink.onCard2)
        }
        trailing?.invoke()
    }
}

/** A switch in the app's colours: white thumb on a dark track, lime-ticked track when on. */
@Composable
fun RSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val ink = Ronie.ink
    val haptics = rememberHaptics()
    Switch(
        checked = checked, onCheckedChange = { haptics.tick(); onCheckedChange(it) }, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color(0xFF0A0A0B), checkedTrackColor = ink.onCard, checkedBorderColor = ink.onCard,
            uncheckedThumbColor = ink.onCard2, uncheckedTrackColor = ink.raised, uncheckedBorderColor = ink.line,
        ),
    )
}

/** A text field in the app's colours: dark fill, soft rounded corners, no harsh outline. */
@Composable
fun RField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    label: String? = null,
    leading: ImageVector? = null,
    readOnly: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val ink = Ronie.ink
    OutlinedTextField(
        value = value, onValueChange = onValueChange, singleLine = singleLine, minLines = minLines, isError = isError,
        readOnly = readOnly, trailingIcon = trailing,
        placeholder = { Text(placeholder, color = ink.onCard3) },
        label = label?.let { { Text(it) } },
        leadingIcon = leading?.let { { Icon(it, contentDescription = null, tint = ink.onCard2) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        shape = RoundedCornerShape(20.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = ink.raised, unfocusedContainerColor = ink.raised,
            focusedBorderColor = ink.onCard2, unfocusedBorderColor = Color.Transparent,
            cursorColor = ink.onCard, focusedTextColor = ink.onCard, unfocusedTextColor = ink.onCard,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** "ACTIVE" / "NO KEY" style pill with a dot. */
@Composable
fun StatusPill(text: String, ok: Boolean, warn: Boolean = false) {
    val s = Ronie.status
    val ink = Ronie.ink
    val dot by animateColorAsState(when { ok -> s.success; warn -> s.warning; else -> ink.onCard3 }, tween(400), label = "dot")
    val blink = if (warn) rememberInfiniteTransition(label = "blink")
        .animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "b").value else 1f
    Surface(shape = PillShape, color = Color.Black.copy(alpha = 0.35f), border = BorderStroke(1.dp, ink.line), modifier = Modifier.animateContentSize(tween(250))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).graphicsLayer { alpha = blink }.clip(CircleShape).background(dot))
            AnimatedContent(text.uppercase(), transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "pill") {
                Text(it, style = MaterialTheme.typography.labelSmall, color = ink.onCard)
            }
        }
    }
}

/** A short status/result line under an action. */
@Composable
fun Note(text: String, color: Color = Ronie.ink.onCard2) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** A lime tick mark, the references' "you are here" marker. */
@Composable
fun Tick(modifier: Modifier = Modifier) {
    Box(modifier.size(width = 3.dp, height = 14.dp).clip(RoundedCornerShape(2.dp)).background(Ronie.ink.tick))
}

// ---------- glass ----------

/**
 * A frosted-glass panel: a faint white (or tinted) fill that's brighter at the top-left, and a hairline edge that
 * catches the light. Reads as glass over [ambientBackdrop] or any glow behind it.
 */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(26.dp),
    tint: Glow? = null,
    padding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val fill = if (tint == null) Brush.linearGradient(listOf(Color.White.copy(alpha = 0.11f), Color.White.copy(alpha = 0.035f)))
    else Brush.linearGradient(listOf(tint.core.copy(alpha = 0.42f), tint.edge.copy(alpha = 0.32f), Color.White.copy(alpha = 0.04f)))
    val edge = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.14f)))
    Column(
        modifier
            .clip(shape)
            .background(Ronie.ink.page.copy(alpha = 0.35f)) // a touch of darkness so text stays readable over bright glows
            .background(fill)
            .border(1.dp, edge, shape)
            .padding(padding),
        content = content,
    )
}

/** Two soft colour blobs drifting slowly behind whatever is drawn on top: the "scene" glass sits on. */
fun Modifier.ambientBackdrop(): Modifier = composed {
    val t by rememberInfiniteTransition(label = "ambient").animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "t")
    drawBehind {
        val w = size.width
        val h = size.height
        fun blob(c: Color, x: Float, y: Float, r: Float) = drawCircle(
            Brush.radialGradient(listOf(c.copy(alpha = 0.55f), c.copy(alpha = 0.18f), Color.Transparent), center = Offset(x, y), radius = r),
            radius = r, center = Offset(x, y),
        )
        val r = w * 0.75f
        blob(Glows.Violet.core, w * (0.15f + 0.12f * cos(t)), h * (0.18f + 0.06f * sin(t * 2)), r)
        blob(Glows.Magenta.core, w * (0.85f + 0.1f * sin(t)), h * (0.55f + 0.08f * cos(t)), r * 0.9f)
        blob(Glows.Mint.edge, w * (0.3f + 0.1f * sin(t + 1)), h * (0.9f + 0.04f * cos(t * 2)), r * 0.8f)
    }
}
