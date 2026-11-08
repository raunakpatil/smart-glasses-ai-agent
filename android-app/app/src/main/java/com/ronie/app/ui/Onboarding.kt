package com.ronie.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.meta.wearable.dat.core.types.RegistrationState
import com.ronie.app.brain.BrainClient
import com.ronie.app.brain.BrainState
import com.ronie.app.glasses.GlassesCamera
import kotlinx.coroutines.delay
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val SETUP_GUIDE = "https://github.com/raunakpatil/smart-glasses-ai-agent/blob/main/docs/SETUP.md"
private const val GEMINI_KEYS = "https://aistudio.google.com/apikey"

/** Opens Google's QR scanner (Play services; no camera permission needed) and hands back what it read. */
fun scanPairingCode(context: Context, onCode: (String) -> Unit, onError: () -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { b -> b.rawValue?.let(onCode) }
        .addOnFailureListener { onError() }
}

private enum class Step { Welcome, Name, Server, Pair, Gemini, Glasses, Done }

/**
 * First run: welcome, your own server, pair with it (scan or paste), the free Gemini key, the glasses, done.
 * Everything here can also be changed later from the app's tabs; [onFinish] is called once at the end.
 */
@Composable
fun Onboarding(
    brain: BrainClient, pair: (String) -> Boolean, connectGlasses: () -> Unit,
    name: String, saveName: (String) -> Unit, onFinish: () -> Unit,
) {
    var step by remember { mutableStateOf(Step.Welcome) }
    val state by brain.state.collectAsState()
    BackHandler(enabled = step != Step.Welcome) { step = Step.entries[step.ordinal - 1] }

    Box(Modifier.fillMaxSize().background(Ronie.ink.page)) {
        AnimatedContent(step, transitionSpec = {
            // Forward slides in from the right, back from the left, like turning pages.
            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (fadeIn(tween(360, 80, EaseOutSoft)) + slideInHorizontally(tween(480, easing = EaseOutSoft)) { it / 6 * dir })
                .togetherWith(fadeOut(tween(180)) + slideOutHorizontally(tween(300)) { -it / 10 * dir })
        }, label = "step") { s ->
            RevealScope {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
                if (s != Step.Welcome && s != Step.Done) StepHeader(s.ordinal, Step.entries.size - 2) { step = Step.entries[s.ordinal - 1] }
                when (s) {
                    Step.Welcome -> Welcome { step = Step.Name }
                    Step.Name -> NameStep(name) { saveName(it); step = Step.Server }
                    Step.Server -> ServerStep { step = Step.Pair }
                    Step.Pair -> PairStep(state, pair) { step = Step.Gemini }
                    Step.Gemini -> GeminiStep(brain, state) { step = Step.Glasses }
                    Step.Glasses -> GlassesStep(connectGlasses) { step = Step.Done }
                    Step.Done -> DoneStep(onFinish)
                }
                Spacer(Modifier.height(32.dp))
            }
            }
        }
    }
}

@Composable
private fun StepHeader(index: Int, total: Int, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        RoundIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(total) { i ->
                val on = i < index
                val w by animateDpAsState(if (i == index - 1) 22.dp else 6.dp, spring(dampingRatio = 0.7f, stiffness = 400f), label = "dotW")
                val c by animateColorAsState(if (on) Ronie.ink.onCard else Ronie.ink.line, tween(300), label = "dotC")
                Box(Modifier.size(width = w, height = 6.dp).clip(CircleShape).background(c))
            }
        }
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun Title(text: String, sub: String) {
    Text(text, style = MaterialTheme.typography.displaySmall, color = Ronie.ink.onCard, modifier = Modifier.reveal())
    Spacer(Modifier.height(10.dp))
    Text(sub, style = MaterialTheme.typography.bodyLarge, color = Ronie.ink.onCard2, modifier = Modifier.reveal())
    Spacer(Modifier.height(28.dp))
}

/** The breathing glow orb, used on the welcome and done screens. */
@Composable
fun GlowOrb(glow: Glow, modifier: Modifier = Modifier, breathing: Boolean = true) {
    val t = rememberInfiniteTransition(label = "orb")
    val k by t.animateFloat(0.82f, if (breathing) 1f else 0.82f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "k")
    Box(modifier.drawBehind {
        val r = size.minDimension / 2 * k
        drawCircle(Brush.radialGradient(
            0f to glow.core, 0.35f to glow.edge, 0.7f to glow.edge.copy(alpha = 0.25f), 1f to Color.Transparent,
            center = Offset(size.width / 2, size.height / 2), radius = r,
        ), radius = r)
    })
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.Start) {
        Box(Modifier.fillMaxWidth().height(340.dp), contentAlignment = Alignment.Center) {
            GlowOrb(Glows.Magenta, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(16.dp))
        Text(buildAnnotatedString {
            append("Ronie Go")
            withStyle(SpanStyle(color = Ronie.ink.live)) { append(".") }
        }, style = MaterialTheme.typography.displayLarge, color = Ronie.ink.onCard)
        Spacer(Modifier.height(8.dp))
        Text("Your Ray-Ban Metas, finally smart.", style = MaterialTheme.typography.headlineSmall, color = Ronie.ink.onCard)
        Spacer(Modifier.height(12.dp))
        Text("Setup takes a few minutes, once. You'll connect your own brain server, add a free Gemini key, and pair your glasses.",
            style = MaterialTheme.typography.bodyLarge, color = Ronie.ink.onCard2)
        Spacer(Modifier.height(32.dp))
        PillAction("Get started", onNext, Modifier.fillMaxWidth(), light = true)
    }
}

@Composable
private fun NameStep(saved: String, onNext: (String) -> Unit) {
    var name by remember { mutableStateOf(saved) }
    Title("What should Ronie call you?", "It's how Ronie greets you on Home. It stays on this phone.")
    RField(name, { name = it }, "Your first name", leading = Icons.Outlined.Person)
    Spacer(Modifier.height(16.dp))
    PillAction("Continue", { onNext(name) }, Modifier.fillMaxWidth(), light = true, enabled = name.isNotBlank())
    Spacer(Modifier.height(10.dp))
    GhostPill("Skip", { onNext("") }, Modifier.fillMaxWidth())
}

@Composable
private fun ServerStep(onNext: () -> Unit) {
    val context = LocalContext.current
    Title("First, your brain.", "Ronie's brain is a small server that you own. It keeps your memory and your keys, so nobody else ever holds them. It's free on Oracle Cloud, or it can run on your own computer.")
    GlowCard(Modifier.fillMaxWidth(), glow = Glows.Mint, from = GlowFrom.TopRight, onClick = onNext) {
        IconBadge(Icons.Outlined.CloudDone, bg = Color.Black.copy(alpha = 0.35f))
        Spacer(Modifier.height(18.dp))
        Text("My server is running", style = MaterialTheme.typography.titleLarge, color = Ronie.ink.onCard)
        Text("I have a pairing code or a QR code from it.", style = MaterialTheme.typography.bodyMedium, color = Ronie.ink.onCard2)
    }
    Spacer(Modifier.height(12.dp))
    GlowCard(Modifier.fillMaxWidth(), glow = Glows.Violet, from = GlowFrom.BottomLeft, strength = 0.8f,
        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SETUP_GUIDE))) }) {
        IconBadge(Icons.Outlined.MenuBook, bg = Color.Black.copy(alpha = 0.35f))
        Spacer(Modifier.height(18.dp))
        Text("I haven't set one up yet", style = MaterialTheme.typography.titleLarge, color = Ronie.ink.onCard)
        Text("Open the step-by-step guide. It takes about 15 minutes and ends with a pairing code. Come back here when you have it.",
            style = MaterialTheme.typography.bodyMedium, color = Ronie.ink.onCard2)
    }
}

@Composable
private fun PairStep(state: BrainState, pair: (String) -> Boolean, onNext: () -> Unit) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var tried by remember { mutableStateOf(false) }

    fun use(text: String) {
        error = null
        if (pair(text)) tried = true
        else error = "That isn't a Ronie pairing code. It starts with {\"app\": \"ronie\"…"
    }

    val haptics = rememberHaptics()
    LaunchedEffect(state, tried) { if (tried && state == BrainState.Connected) { haptics.confirm(); delay(900); onNext() } }
    if (state == BrainState.Connected) tried = true // already paired (setup run again): nothing to do here

    Title("Connect to your brain.", "Scan the QR code on your server's pairing page, or paste the pairing code it printed.")

    GlowCard(Modifier.fillMaxWidth(), glow = when (state) {
        BrainState.Connected -> Glows.Mint
        BrainState.Rejected -> Glows.Coral
        else -> Glows.Violet
    }, from = GlowFrom.Top, strength = if (tried) 1f else 0.55f) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusPill(
                when {
                    !tried -> "Not paired"
                    state == BrainState.Connected -> "Connected"
                    state == BrainState.Rejected -> "Code not accepted"
                    else -> "Connecting"
                },
                ok = tried && state == BrainState.Connected, warn = tried && state != BrainState.Connected,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                !tried -> "Waiting for a code"
                state == BrainState.Connected -> "You're connected."
                state == BrainState.Rejected -> "Your server said no."
                else -> "Reaching your server…"
            },
            style = MaterialTheme.typography.headlineMedium, color = Ronie.ink.onCard,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                !tried -> "On the server, run  ronie-brain pairing-code  or open its /pair page."
                state == BrainState.Connected -> "Next, the AI key."
                state == BrainState.Rejected -> "The token in this code is old. Get a fresh code from the server and try again."
                else -> "If this takes long, check the server is running and the code has your server's wss:// address."
            },
            style = MaterialTheme.typography.bodyMedium, color = Ronie.ink.onCard2,
        )
    }
    Spacer(Modifier.height(16.dp))
    PillAction("Scan QR code", light = true, icon = Icons.Outlined.QrCodeScanner, modifier = Modifier.fillMaxWidth(), onClick = {
        scanPairingCode(context, ::use) { error = "Couldn't open the scanner. Paste the code instead." }
    })
    Spacer(Modifier.height(12.dp))
    RField(code, { code = it; error = null }, "Or paste the pairing code", singleLine = false, minLines = 2, isError = error != null)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostPill("Paste", icon = Icons.Outlined.ContentPaste, modifier = Modifier.weight(1f), onClick = {
            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            clip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { code = it }
        })
        GhostPill("Connect", enabled = code.isNotBlank(), modifier = Modifier.weight(1f), onClick = { use(code) })
    }
    error?.let { Spacer(Modifier.height(10.dp)); Note(it, Ronie.status.danger) }
    if (tried && state == BrainState.Connected) {
        Spacer(Modifier.height(16.dp))
        PillAction("Continue", onNext, Modifier.fillMaxWidth())
    }
}

@Composable
private fun GeminiStep(brain: BrainClient, state: BrainState, onNext: () -> Unit) {
    val context = LocalContext.current
    var hasKey by remember { mutableStateOf<Boolean?>(null) }
    var key by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    suspend fun check() {
        hasKey = runCatching {
            brain.request("status.get", buildJsonObject {})["providers"]?.jsonObject?.get("gemini")?.jsonObject
                ?.get("has_key")?.jsonPrimitive?.booleanOrNull == true
        }.getOrNull()
    }
    LaunchedEffect(state) { if (state == BrainState.Connected) check() }
    LaunchedEffect(saving) { if (saving) { delay(1200); check(); saving = false; if (hasKey == true) key = "" } }

    Title("Add your free AI key.", "Ronie's voice runs on Google Gemini. A key is free and takes a minute. It's saved on your server, never on this phone.")

    GlowCard(Modifier.fillMaxWidth(), glow = if (hasKey == true) Glows.Mint else Glows.Ice, from = GlowFrom.TopRight) {
        StatusPill(when (hasKey) { true -> "Gemini ready"; false -> "No key yet"; null -> "Checking" }, ok = hasKey == true, warn = hasKey == false)
        Spacer(Modifier.height(16.dp))
        Text(if (hasKey == true) "Gemini is ready." else "Paste your Gemini key", style = MaterialTheme.typography.headlineMedium, color = Ronie.ink.onCard)
        if (hasKey != true) {
            Spacer(Modifier.height(6.dp))
            Text("Google AI Studio → Get API key → Create. Copy it, then paste it here.", style = MaterialTheme.typography.bodyMedium, color = Ronie.ink.onCard2)
        }
    }
    Spacer(Modifier.height(16.dp))
    if (hasKey == true) {
        PillAction("Continue", onNext, Modifier.fillMaxWidth(), light = true)
        Spacer(Modifier.height(10.dp))
        Note("Want Groq, OpenRouter, Claude or ChatGPT too? Add them any time in the AI tab.")
        return
    }
    GhostPill("Get a free key", icon = Icons.Outlined.OpenInNew, modifier = Modifier.fillMaxWidth(),
        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GEMINI_KEYS))) })
    Spacer(Modifier.height(12.dp))
    RField(key, { key = it }, "Paste the key (starts with AIza…)", secret = true)
    Spacer(Modifier.height(12.dp))
    PillAction("Save key", modifier = Modifier.fillMaxWidth(), light = true, enabled = key.isNotBlank() && state == BrainState.Connected, loading = saving,
        onClick = { brain.setKey("gemini", key.trim()); saving = true })
    Spacer(Modifier.height(10.dp))
    GhostPill("I'll do this later", onNext, Modifier.fillMaxWidth())
}

@Composable
private fun GlassesStep(connect: () -> Unit, onNext: () -> Unit) {
    val registration by GlassesCamera.registration.collectAsState()
    val done = registration == RegistrationState.REGISTERED
    Title("Now, your glasses.", "Pair your Ray-Ban Metas with your phone in the Meta AI app first. Then let Ronie use them.")

    GlowCard(Modifier.fillMaxWidth(), glow = if (done) Glows.Mint else Glows.Rose, from = GlowFrom.Top, strength = 0.85f) {
        listOf(
            "Open the Meta AI app → Settings → App info, and tap the version number five times to turn on Developer Mode.",
            "Tap Connect below and approve Ronie in the Meta AI app.",
            "Come back here. Ronie can now hear you and see through the glasses.",
        ).forEachIndexed { i, t ->
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DotNumber("${i + 1}", dot = 3.dp, color = Ronie.ink.onCard)
                Text(t, style = MaterialTheme.typography.bodyMedium, color = Ronie.ink.onCard)
            }
        }
        Spacer(Modifier.height(8.dp))
        StatusPill(if (done) "Glasses connected" else if (registration == RegistrationState.REGISTERING) "Waiting for Meta AI" else "Not connected", ok = done)
    }
    Spacer(Modifier.height(16.dp))
    if (done) PillAction("Continue", onNext, Modifier.fillMaxWidth(), light = true)
    else {
        PillAction("Connect glasses", connect, Modifier.fillMaxWidth(), light = true, icon = Icons.Outlined.Bluetooth,
            loading = registration == RegistrationState.REGISTERING)
        Spacer(Modifier.height(10.dp))
        GhostPill("Skip, use the phone for now", onNext, Modifier.fillMaxWidth())
    }
}

@Composable
private fun DoneStep(onFinish: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
            GlowOrb(Glows.Mint, Modifier.fillMaxSize())
            Icon48(Icons.Outlined.Check)
        }
        Spacer(Modifier.height(16.dp))
        Text("You're all set.", style = MaterialTheme.typography.displaySmall, color = Ronie.ink.onCard)
        Spacer(Modifier.height(10.dp))
        Text("Put on your glasses and say \"Hey Ronie\". Try \"What am I looking at?\" first.", style = MaterialTheme.typography.bodyLarge, color = Ronie.ink.onCard2)
        Spacer(Modifier.height(32.dp))
        PillAction("Start using Ronie", onFinish, Modifier.fillMaxWidth(), light = true)
    }
}

@Composable
private fun Icon48(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(72.dp).clip(CircleShape).background(Ronie.ink.onCard), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = Color(0xFF0A0A0B), modifier = Modifier.size(34.dp))
    }
}
