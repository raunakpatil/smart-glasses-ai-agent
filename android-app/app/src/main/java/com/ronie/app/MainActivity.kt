package com.ronie.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.RegistrationState
import com.ronie.app.brain.BrainClient
import com.ronie.app.brain.BrainState
import com.ronie.app.glasses.GlassesCamera
import com.ronie.app.tv.TvRemote
import com.ronie.app.ui.*
import com.ronie.app.voice.Battery
import com.ronie.app.voice.GlassesMonitor
import com.ronie.app.voice.VoiceController
import com.ronie.app.voice.VoiceService
import com.ronie.app.voice.VoiceStatus
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put


val MODES = listOf("Assistant", "Interview mode", "Interview practice", "Coding", "Game", "Translator", "Focus", "Cooking")

fun brainMode(mode: String) = when (mode) {
    "Interview mode" -> "interview_live"
    "Interview practice" -> "interview_practice"
    else -> "assistant"
}

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Outlined.Home),
    Modes("Modes", Icons.Outlined.GridView),
    Memory("Memory", Icons.Outlined.Psychology),
    Devices("Devices", Icons.Outlined.Devices),
    Keys("AI", Icons.Outlined.AutoAwesome)
}

data class ModeCardData(val name: String, val desc: String, val icon: ImageVector)

val APP_MODES = listOf(
    ModeCardData("Assistant", "Answers, web search, your TV and laptop", Icons.Outlined.AutoAwesome),
    ModeCardData("Interview mode", "Quiet answer points in a real interview", Icons.Outlined.WorkOutline),
    ModeCardData("Interview practice", "Mock interviews with spoken feedback", Icons.Outlined.RecordVoiceOver),
    ModeCardData("Coding", "A hands-free thinking partner for code", Icons.Outlined.Code),
    ModeCardData("Translator", "Two-way voice translation", Icons.Outlined.Translate),
    ModeCardData("Game", "Chess moves, poker odds, rules referee", Icons.Outlined.SportsEsports),
    ModeCardData("Focus", "A calm study and thinking coach", Icons.Outlined.SelfImprovement),
    ModeCardData("Cooking", "Step-by-step recipes, swaps and timers", Icons.Outlined.Restaurant),
)

val KEY_PROVIDERS = listOf(
    "gemini" to "Gemini · free, needed for voice",
    "groq" to "Groq · free",
    "openrouter" to "OpenRouter · free models",
    "claude" to "Claude · paid",
    "openai" to "ChatGPT (OpenAI) · paid",
    "deepseek" to "DeepSeek · paid",
    "tavily" to "Tavily web search · optional"
)

class MainActivity : ComponentActivity() {
    private val app get() = application as RonieApplication

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) VoiceService.start(this)
    }
    private val askForReady = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) VoiceService.ready(this)
    }

    private fun startReady() {
        val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) VoiceService.ready(this) else askForReady.launch(needed.toTypedArray())
    }

    private fun setAutoListen(on: Boolean) {
        app.autoListen = on
        if (on) startReady() else VoiceService.stop(this)
    }

    override fun onStart() {
        super.onStart()
        // Permissions may have changed in Settings while Ronie was in the background.
        GlassesCamera.refreshPermissions()
        if (app.autoListen && app.onboarded) startReady()
    }

    private val askBluetooth = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        GlassesCamera.refreshPermissions()
        if (granted[Manifest.permission.BLUETOOTH_CONNECT] == true) Wearables.startRegistration(this)
    }
    private val askNearby = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        GlassesCamera.refreshPermissions()
    }
    private val askGlassesCamera = registerForActivityResult(Wearables.RequestPermissionContract()) { _ -> }

    // The Wearables SDK needs both: CONNECT to talk to the glasses, SCAN to keep finding them after
    // the link drops. Without SCAN the first camera session works, then every later one fails with
    // "The glasses aren't connected" while the link stays DISCONNECTED.
    private fun missingNearbyPermissions() = listOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
    ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun connectGlasses() {
        val missing = missingNearbyPermissions()
        if (missing.isEmpty()) {
            Wearables.startRegistration(this)
        } else {
            askBluetooth.launch(missing.toTypedArray())
        }
    }

    private fun allowNearby() {
        val missing = missingNearbyPermissions()
        if (missing.isEmpty()) GlassesCamera.refreshPermissions() else askNearby.launch(missing.toTypedArray())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Wearables.handleIntent(intent) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RonieTheme {
                var onboarded by remember { mutableStateOf(app.onboarded) }
                if (!onboarded) {
                    Onboarding(brain = app.brain, pair = app::pair, connectGlasses = ::connectGlasses,
                        name = app.userName, saveName = { app.userName = it }) {
                        app.onboarded = true
                        onboarded = true
                        if (app.autoListen) startReady()
                    }
                } else RonieApp(
                    brain = app.brain,
                    voice = app.voice,
                    glasses = app.glasses,
                    pair = app::pair,
                    onTalk = ::toggleTalk,
                    autoListen = app.autoListen,
                    setAutoListen = ::setAutoListen,
                    savedMode = app.modeFlow,
                    saveMode = { app.mode = it },
                    camera = CameraActions(
                        connect = ::connectGlasses,
                        allowNearby = ::allowNearby,
                        allowCamera = { askGlassesCamera.launch(Permission.CAMERA) },
                        updateGlasses = { Wearables.openDATGlassesAppUpdate(this) },
                    ),
                    runSetup = { app.onboarded = false; onboarded = false },
                )
            }
        }
    }

    private fun toggleTalk() {
        if (app.voice.running) {
            if (app.autoListen) app.voice.stop() else VoiceService.stop(this)
            return
        }
        val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) VoiceService.start(this) else askPermissions.launch(needed.toTypedArray())
    }
}

@Composable
fun RonieApp(
    brain: BrainClient, voice: VoiceController, glasses: GlassesMonitor, pair: (String) -> Boolean, onTalk: () -> Unit,
    savedMode: kotlinx.coroutines.flow.StateFlow<String>, saveMode: (String) -> Unit,
    autoListen: Boolean, setAutoListen: (Boolean) -> Unit,
    camera: CameraActions, runSetup: () -> Unit,
) {
    var auto by rememberSaveable { mutableStateOf(autoListen) }
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val mode by savedMode.collectAsState() // also changes when the user switches mode by voice
    var premium by rememberSaveable { mutableStateOf(false) }
    val state by brain.state.collectAsState()

    Box(Modifier.fillMaxSize().background(Ronie.ink.page)) {
        // The header stays put; the page under it slides toward the tab you picked and fades.
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
        AppHeader(state, Modifier.padding(horizontal = 16.dp))
        AnimatedContent(
            tab,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (fadeIn(tween(320, delayMillis = 60, easing = EaseOutSoft)) + slideInHorizontally(tween(420, easing = EaseOutSoft)) { it / 10 * dir })
                    .togetherWith(fadeOut(tween(160)) + slideOutHorizontally(tween(260)) { -it / 14 * dir })
            },
            label = "tab",
        ) { shownTab ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val firstScreen = maxHeight - navInset - 100.dp // what's visible above the floating tab bar
        RevealScope {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 120.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (shownTab) {
                Tab.Home -> HomeScreen(brain, state, mode, voice, glasses, onTalk, onOpenModes = { tab = Tab.Modes }, firstScreen = firstScreen,
                    onOpenMemory = { tab = Tab.Memory })
                Tab.Modes -> {
                    ModesScreen(brain, mode) { saveMode(it) }
                    val voices by brain.voices.collectAsState()
                    VoiceScreen(voices, onSelect = brain::setVoice)
                }
                Tab.Memory -> MemoryScreen(brain)
                Tab.Devices -> {
                    ScreenTitle("Devices", "Your glasses, TV, laptop and the brain they all talk to.")
                    ListeningSection(auto) {
                        auto = it
                        setAutoListen(it)
                    }
                    GlassesCameraSection(camera)
                    TvRemoteSection()
                    LaptopControlSection(brain)
                    BrainSection(state, pair, runSetup)
                }
                Tab.Keys -> KeysScreen(brain, premium, onPremium = {
                    premium = it
                    brain.updateSettings(buildJsonObject { put("premium", it) })
                }, onSaveKey = brain::setKey, onRemoveKey = brain::deleteKey)
            }
        }
        }
        }
        }
        }
        // Fade the page out under the floating bar.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(140.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Ronie.ink.page.copy(alpha = 0.92f), Ronie.ink.page))))
        FloatingNav(tab, { tab = it }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp))
    }
}

/**
 * The floating tab bar: the current tab is a white ball resting in line with the icons, in a snug pocket pressed into
 * the bar. Tapping another tab lifts the ball out (the pocket closes), carries it across in a smooth arc, and the
 * pocket opens round it as it lands. The first and last tabs sit at the bar's rounded ends, so there the pocket fades
 * away and the ball rests in the bar like a normal button.
 *
 * Everything that moves is read from one animation inside drawing and graphics layers, so the bar never recomposes
 * mid-hop; that keeps it smooth.
 */
@Composable
fun FloatingNav(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val ink = Ronie.ink
    val haptics = rememberHaptics()
    val tabs = Tab.entries
    val travel = remember { Animatable(1f) }   // the hop, 0 → 1
    var from by remember { mutableStateOf(current.ordinal) }
    var to by remember { mutableStateOf(current.ordinal) }
    LaunchedEffect(current) {
        if (current.ordinal == to) return@LaunchedEffect
        from = if (travel.value < 0.5f) from else to // a tap mid-hop starts from the nearer tab
        to = current.ordinal
        travel.snapTo(0f)
        travel.animateTo(1f, tween(340, easing = androidx.compose.animation.core.CubicBezierEasing(0.45f, 0f, 0.2f, 1f)))
    }
    val landed by remember { derivedStateOf { travel.value >= 0.5f } } // which icon the ball shows

    val ballR = 22.dp
    val lift = 22.dp
    val above = 14.dp      // room over the bar for the hop
    val barH = 70.dp
    val inset = 16.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(modifier.padding(horizontal = 22.dp).fillMaxWidth().height(above + barH)) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = with(density) { maxWidth.toPx() }
            val ins = with(density) { inset.toPx() }
            val slotPx = (w - 2 * ins) / tabs.size
            fun cx(i: Int) = ins + slotPx * i + slotPx / 2
            fun progress() = travel.value.coerceIn(0f, 1f)
            fun ballX() = cx(from) + (cx(to) - cx(from)) * progress()
            // pocket depth: closes as the ball lifts, opens as it lands; none at the end tabs
            fun pocket(): Float {
                val hop = kotlin.math.sin(progress() * Math.PI.toFloat())
                val open = (1f - 1.7f * hop).coerceIn(0f, 1f)
                val x = ballX()
                val edge = (((x - cx(0)) / slotPx).coerceIn(0f, 1f) * ((cx(tabs.size - 1) - x) / slotPx).coerceIn(0f, 1f))
                    .let { kotlin.math.sqrt(it) }
                return open * edge
            }

            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val top = above.toPx()
                val r = ballR.toPx()
                val gap = 5.dp.toPx()
                val iconY = top + barH.toPx() / 2
                val bar = androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, top, size.width, size.height, androidx.compose.ui.geometry.CornerRadius(32.dp.toPx())))
                }
                val shape = pocket().let { k ->
                    if (k <= 0.01f) bar else {
                        // a snug pocket: rounded shoulders at the top edge, straight walls a gap away from the ball,
                        // and a round bottom that wraps it; shallower while it opens or closes
                        val c = ballX()
                        val R = r + gap
                        val fil = 13.dp.toPx() * k
                        val bottom = top + (iconY + R - top) * k
                        val h = minOf(R, (bottom - top).coerceAtLeast(0.01f))
                        val pocketPath = androidx.compose.ui.graphics.Path().apply {
                            moveTo(c - R - fil, top - 1f)
                            quadraticBezierTo(c - R, top - 1f, c - R, top + fil)
                            lineTo(c - R, maxOf(top + fil, bottom - h))
                            arcTo(androidx.compose.ui.geometry.Rect(c - R, bottom - 2 * h, c + R, bottom), 180f, -180f, false)
                            lineTo(c + R, top + fil)
                            quadraticBezierTo(c + R, top - 1f, c + R + fil, top - 1f)
                            lineTo(c + R + fil, 0f); lineTo(c - R - fil, 0f); close()
                        }
                        androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, bar, pocketPath)
                    }
                }
                drawPath(shape, ink.raised.copy(alpha = 0.97f))
                drawPath(shape, ink.line, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
            // the icons; the current one lives in the ball instead (faded by the same animation)
            Row(Modifier.fillMaxWidth().height(barH).align(Alignment.BottomStart).padding(horizontal = inset)) {
                tabs.forEach { tab ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .clickable(remember { MutableInteractionSource() }, indication = null) { if (tab != current) haptics.tick(); onSelect(tab) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(tab.icon, contentDescription = tab.label, tint = ink.onCard2, modifier = Modifier.size(23.dp).graphicsLayer {
                            val t = progress()
                            alpha = when (tab.ordinal) {
                                to -> if (from == to) 0f else (1f - t * 2f).coerceIn(0f, 1f)
                                from -> (t * 2f - 1f).coerceIn(0f, 1f)
                                else -> 1f
                            }
                        })
                    }
                }
            }
            // the ball: moved by its layer only
            Box(
                Modifier
                    .offset(y = above + barH / 2 - ballR)
                    .size(ballR * 2)
                    .graphicsLayer {
                        val t = progress()
                        val hopK = kotlin.math.sin(t * Math.PI.toFloat())
                        translationX = ballX() - ballR.toPx()
                        translationY = -lift.toPx() * hopK
                        val sq = 1f + 0.04f * hopK
                        scaleY = sq; scaleX = 2f - sq
                    }
                    .clip(CircleShape)
                    .background(ink.onCard),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.animation.Crossfade(tabs[if (landed) to else from], animationSpec = tween(110), label = "ballIcon") {
                    Icon(it.icon, contentDescription = null, tint = Color(0xFF0A0A0B), modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
fun AppHeader(brainState: BrainState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(buildAnnotatedString {
            append("Ronie")
            withStyle(SpanStyle(color = Ronie.ink.live)) { append(".") }
        }, style = MaterialTheme.typography.headlineLarge, color = Ronie.ink.onCard)
        when (brainState) {
            BrainState.Connected -> StatusPill("Online", ok = true)
            BrainState.Connecting -> StatusPill("Connecting", ok = false, warn = true)
            BrainState.Rejected -> StatusPill("Pair again", ok = false, warn = true)
            BrainState.Disconnected -> StatusPill("Offline", ok = false)
        }
    }
}

@Composable
fun HomeScreen(
    brain: BrainClient, state: BrainState, mode: String, voice: VoiceController, glasses: GlassesMonitor, onTalk: () -> Unit,
    onOpenModes: () -> Unit, firstScreen: androidx.compose.ui.unit.Dp,
    onOpenMemory: () -> Unit,
) {
    val glassesName by glasses.name.collectAsState()
    val status by voice.status.collectAsState()
    val detail by voice.detail.collectAsState()
    val lines by voice.lines.collectAsState()
    val context = LocalContext.current
    val glassesBattery = remember(glassesName) { glassesName?.let { Battery.glasses(context)?.second } ?: -1 }
    val ink = Ronie.ink

    // 1 + 2: the first screen, exactly what fits above the tab bar: the voice card, then two glance tiles.
    val talking = status != VoiceStatus.Off
    val live = status == VoiceStatus.Listening || status == VoiceStatus.Speaking
    val glow = when (status) {
        VoiceStatus.Listening -> Glows.Magenta
        VoiceStatus.Speaking -> Glows.Violet
        VoiceStatus.Error -> Glows.Coral
        VoiceStatus.Waiting, VoiceStatus.Starting -> Glows.Ice
        VoiceStatus.Off -> Glows.Violet
    }
    Column(Modifier.fillMaxWidth().height(firstScreen.coerceAtLeast(560.dp)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    GlowCard(
        Modifier.fillMaxWidth().weight(1f), glow = glow, from = GlowFrom.Center,
        strength = when { live -> 1f; talking -> 0.75f; else -> 0.5f }, padding = 22.dp, driftMs = 36000, orbit = true,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MonoLabel(when (status) {
                VoiceStatus.Off -> "Standby"
                VoiceStatus.Starting -> "Starting"
                VoiceStatus.Waiting -> "Waiting for its name"
                VoiceStatus.Listening -> "Listening"
                VoiceStatus.Speaking -> "Speaking"
                VoiceStatus.Error -> "Error"
            }, icon = Icons.Outlined.GraphicEq, color = ink.onCard)
            StatusPill(if (glassesName != null) "Glasses" else "Phone", ok = glassesName != null)
        }
        Spacer(Modifier.weight(1f))
        // The centrepiece: what Ronie is doing, then the settings you reach for most.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            val app = LocalContext.current.applicationContext as RonieApplication
            val name by app.nameFlow.collectAsState()
            val headline = when (status) {
                VoiceStatus.Off -> if (state == BrainState.Connected) greeting(name) else "Connecting…"
                VoiceStatus.Starting -> "Getting ready"
                VoiceStatus.Waiting -> "Say \"Ronie\""
                VoiceStatus.Listening -> "Listening"
                VoiceStatus.Speaking -> "Speaking"
                VoiceStatus.Error -> "Something went wrong"
            }
            // The new state rises in as the old one lifts away.
            AnimatedContent(headline, transitionSpec = {
                (fadeIn(tween(320, 60, EaseOutSoft)) + slideInVertically(tween(420, easing = EaseOutSoft)) { it / 3 })
                    .togetherWith(fadeOut(tween(160)) + slideOutVertically(tween(220)) { -it / 4 })
            }, label = "headline", contentAlignment = Alignment.CenterStart) {
                Text(it, style = MaterialTheme.typography.displaySmall, color = ink.onCard)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = when (status) {
                    VoiceStatus.Off -> "Tap to talk, or say \"Hey Ronie\" with your glasses on"
                    VoiceStatus.Starting -> detail.ifBlank { "Connecting the voice" }
                    VoiceStatus.Waiting -> "Ronie wakes when you say its name"
                    VoiceStatus.Listening -> when {
                        detail.isNotBlank() && detail != "Listening" -> detail
                        glassesName != null -> "Through your glasses"
                        else -> "Through the phone"
                    }
                    VoiceStatus.Speaking -> "Say \"stop\" to interrupt"
                    VoiceStatus.Error -> detail
                },
                style = MaterialTheme.typography.bodyMedium, color = ink.onCard.copy(alpha = 0.66f), maxLines = 2,
            )
            Spacer(Modifier.height(28.dp))
            RonieKnows(brain, state, onOpenMemory)
        }
        Spacer(Modifier.weight(1f))
        VoiceLine(status, Modifier.fillMaxWidth().height(56.dp))
        Spacer(Modifier.height(4.dp))
        AnimatedContent(talking, transitionSpec = {
            (fadeIn(tween(300, 80)) + scaleIn(tween(360, easing = EaseOutSoft), initialScale = 0.85f)).togetherWith(fadeOut(tween(150)))
        }, label = "talkButton", modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { on ->
            if (on) StopButton(onTalk)
            else TalkButton(onTalk, enabled = state == BrainState.Connected)
        }
    }

    Row(Modifier.fillMaxWidth().height(158.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Glasses: a battery ring, like the reference's "Average glucose" dial.
        GlowCard(Modifier.weight(1f).fillMaxHeight(), glow = Glows.Mint, from = GlowFrom.BottomLeft, strength = if (glassesName != null) 0.75f else 0.3f, padding = 18.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                MonoLabel("Glasses", icon = Icons.Outlined.Visibility, color = ink.onCard)
                BatteryRing(glassesBattery.takeIf { it >= 0 }, connected = glassesName != null)
            }
            Spacer(Modifier.weight(1f))
            Text(if (glassesName != null) "Connected" else "Not on", style = MaterialTheme.typography.titleLarge, color = ink.onCard)
            Text(glassesName ?: "Using the phone mic", style = MaterialTheme.typography.bodySmall, color = ink.onCard.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Mode: its name and a row of dots, one per mode, the current one lit (the reference's "Variability").
        GlowCard(Modifier.weight(1f).fillMaxHeight(), glow = Glows.Magenta, from = GlowFrom.TopRight, strength = 0.6f, onClick = onOpenModes, padding = 18.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                MonoLabel("Mode", icon = Icons.Outlined.GridView, color = ink.onCard)
                Icon(Icons.Outlined.ChevronRight, contentDescription = "Change mode", tint = ink.onCard2, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(10.dp))
            // The current mode's own icon, the same one its card on the Modes tab shows.
            AnimatedContent(APP_MODES.firstOrNull { it.name == mode }?.icon ?: Icons.Outlined.AutoAwesome, transitionSpec = {
                (fadeIn(tween(300)) + scaleIn(tween(400, easing = EaseOutSoft), initialScale = 0.7f)).togetherWith(fadeOut(tween(150)))
            }, label = "modeIcon") { IconBadge(it, bg = Color.Black.copy(alpha = 0.35f), size = 40.dp) }
            Spacer(Modifier.weight(1f))
            Text(mode, style = MaterialTheme.typography.titleLarge, color = ink.onCard, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Tap or say \"switch to…\"", style = MaterialTheme.typography.bodySmall, color = ink.onCard.copy(alpha = 0.6f), maxLines = 1)
        }
    }
    }

    // 3. The conversation, newest first
    Spacer(Modifier.height(96.dp)) // starts below the tab bar, so the first screen stays clean until you scroll
    SectionLabel(if (lines.isEmpty()) "Things to try" else "Conversation · ${lines.size}")
    if (lines.isEmpty()) {
        GlowCard(Modifier.fillMaxWidth(), padding = 8.dp) {
            PromptSuggestionItem(Icons.Outlined.Visibility, "\"What am I looking at?\"", "Takes a photo with your glasses")
            PromptSuggestionItem(Icons.Outlined.Tv, "\"Play Stranger Things on the TV\"", "Opens it on your TV")
            PromptSuggestionItem(Icons.Outlined.Laptop, "\"Play lofi on my laptop\"", "Plays it in your laptop's browser")
            PromptSuggestionItem(Icons.Outlined.SportsEsports, "\"What's my best move?\"", "Reads the chess board in front of you")
            PromptSuggestionItem(Icons.Outlined.TravelExplore, "\"Who won the F1 race today?\"", "Searches the web and sums it up")
        }
    } else {
        // Frosted glass over a slowly drifting wash of colour (references: the blurred-photo profile and casino cards).
        Box(Modifier.fillMaxWidth().ambientBackdrop()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                lines.takeLast(10).asReversed().forEachIndexed { i, line ->  // newest first, no scrolling needed
                    key(lines.size - i) { // keyed by position in the whole chat, so only new lines rise in
                        Box(Modifier.reveal(0)) { ChatLine(line, newest = i == 0) }
                    }
                }
            }
        }
    }
}

/** One line of the conversation as a glass bubble: Ronie's clear on the left, yours tinted on the right. */
@Composable
fun ChatLine(line: com.ronie.app.voice.Line, newest: Boolean) {
    val ink = Ronie.ink
    when (line.who) {
        "cue" -> Glass(Modifier.fillMaxWidth(), tint = Glows.Coral) {
            MonoLabel("Interview · say this", icon = Icons.Outlined.Lightbulb, color = ink.onCard)
            Spacer(Modifier.height(8.dp))
            Text(line.text, style = MaterialTheme.typography.titleMedium, color = ink.onCard)
        }
        "you" -> Box(Modifier.fillMaxWidth().padding(start = 48.dp), contentAlignment = Alignment.CenterEnd) {
            Glass(shape = RoundedCornerShape(26.dp, 26.dp, 8.dp, 26.dp), tint = Glows.Magenta, padding = PaddingValues(horizontal = 18.dp, vertical = 13.dp)) {
                Text(line.text, style = MaterialTheme.typography.bodyLarge, color = ink.onCard)
            }
        }
        "room" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Glass(shape = PillShape, padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                MonoLabel("Heard · ${line.text}", icon = Icons.Outlined.Hearing, color = ink.onCard2)
            }
        }
        else -> Box(Modifier.fillMaxWidth().padding(end = 36.dp), contentAlignment = Alignment.CenterStart) {
            Glass(shape = RoundedCornerShape(26.dp, 26.dp, 26.dp, 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RonieOrb(live = newest)
                    Text("Ronie", style = MaterialTheme.typography.labelLarge, color = ink.onCard)
                }
                Spacer(Modifier.height(8.dp))
                Text(line.text, style = MaterialTheme.typography.bodyLarge, color = ink.onCard.copy(alpha = 0.92f))
            }
        }
    }
}

/** Ronie's little avatar: a glowing magenta-violet orb; the newest reply's orb slowly turns. */
@Composable
fun RonieOrb(live: Boolean, size: androidx.compose.ui.unit.Dp = 22.dp) {
    val turn = if (live) rememberInfiniteTransition(label = "orb").animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = androidx.compose.animation.core.LinearEasing)), label = "r").value else 0f
    Box(Modifier.size(size).graphicsLayer { rotationZ = turn }.clip(CircleShape).background(
        Brush.sweepGradient(listOf(Glows.Magenta.core, Glows.Violet.core, Glows.Ice.core, Glows.Magenta.core)),
    ), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 0.45f).clip(CircleShape).background(Color.White.copy(alpha = 0.85f)))
    }
}

@Composable
fun PromptSuggestionItem(icon: ImageVector, prompt: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon)
        Column(Modifier.weight(1f)) {
            Text(prompt, style = MaterialTheme.typography.titleSmall, color = Ronie.ink.onCard)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Ronie.ink.onCard2)
        }
    }
}

@Composable
fun ModesScreen(brain: BrainClient, current: String, onSelect: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var jobDescription by remember { mutableStateOf("") }
    var jobStatus by remember { mutableStateOf<String?>(null) }
    val ink = Ronie.ink

    ScreenTitle("Modes", "What Ronie helps with. Or just say \"switch to game mode\".")

    APP_MODES.chunked(2).forEachIndexed { row, pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEachIndexed { col, m ->
                val isSelected = m.name == current
                val glow = Glows.all[(row * 2 + col) % Glows.all.size]
                GlowCard(
                    Modifier.weight(1f).height(184.dp), glow = glow,
                    from = if (col == 0) GlowFrom.BottomLeft else GlowFrom.TopRight,
                    strength = if (isSelected) 1f else 0.35f, selected = isSelected, padding = 18.dp,
                    onClick = { onSelect(m.name) },
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        IconBadge(m.icon, bg = Color.Black.copy(alpha = 0.35f), size = 40.dp)
                        AnimatedVisibility(isSelected, enter = scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                            Box(Modifier.size(26.dp).clip(CircleShape).background(ink.onCard), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Check, contentDescription = "Current mode", tint = Color(0xFF0A0A0B), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(m.name, style = MaterialTheme.typography.titleMedium, color = ink.onCard)
                    Spacer(Modifier.height(4.dp))
                    Text(m.desc, style = MaterialTheme.typography.bodySmall, color = ink.onCard.copy(alpha = 0.7f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    // Settings for the chosen mode only.
    AnimatedVisibility(current == "Game", enter = fadeIn(tween(300)) + expandVertically(tween(400, easing = EaseOutSoft)), exit = fadeOut(tween(150)) + shrinkVertically(tween(300))) {
        GameModeCard(brain)
    }

    AnimatedVisibility(current.startsWith("Interview"), enter = fadeIn(tween(300)) + expandVertically(tween(400, easing = EaseOutSoft)), exit = fadeOut(tween(150)) + shrinkVertically(tween(300))) {
    GlowCard(Modifier.fillMaxWidth(), glow = Glows.Violet, from = GlowFrom.TopRight, strength = 0.5f) {
        MonoLabel("The job", icon = Icons.Outlined.WorkOutline, color = ink.onCard)
        Spacer(Modifier.height(10.dp))
        Text("Paste the job description", style = MaterialTheme.typography.titleLarge, color = ink.onCard)
        Text("Ronie shapes its answer points around this role.", style = MaterialTheme.typography.bodySmall, color = ink.onCard2)
        Spacer(Modifier.height(14.dp))
        RField(jobDescription, { jobDescription = it }, "Requirements and responsibilities…", singleLine = false, minLines = 4)
        Spacer(Modifier.height(12.dp))
        PillAction("Save", light = true, enabled = jobDescription.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                jobStatus = "Saving…"
                jobStatus = try {
                    brain.request("interview.job", buildJsonObject { put("text", jobDescription) })
                    "Saved. Ronie will use it for interview answers."
                } catch (e: Exception) {
                    "Couldn't save: ${e.message}"
                }
            }
        })
        jobStatus?.let { Spacer(Modifier.height(8.dp)); Note(it) }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameModeCard(brain: BrainClient) {
    val scope = rememberCoroutineScope()
    var activeSubGame by remember { mutableStateOf("chess") } // chess, poker, referee
    var coachingStyle by remember { mutableStateOf("coach") } // coach, teach, referee, review
    var statusText by remember { mutableStateOf<String?>(null) }
    var spokenAdvice by remember { mutableStateOf<String?>(null) }
    var isEvaluating by remember { mutableStateOf(false) }
    val ink = Ronie.ink

    // Chess state
    var chessMoveInput by remember { mutableStateOf("") }
    var chessBestMove by remember { mutableStateOf<String?>(null) }
    var chessEval by remember { mutableStateOf<String?>(null) }

    // Poker state
    var pokerHole by remember { mutableStateOf("Ah Kd") }
    var pokerBoard by remember { mutableStateOf("") }
    var pokerPot by remember { mutableStateOf("100") }
    var pokerToCall by remember { mutableStateOf("20") }
    var pokerEquity by remember { mutableStateOf<String?>(null) }
    var pokerRec by remember { mutableStateOf<String?>(null) }

    // Rules referee
    var refereeGame by remember { mutableStateOf("Monopoly") }
    var refereeTopic by remember { mutableStateOf("jail") }
    var refereeVerdict by remember { mutableStateOf<String?>(null) }
    var capturedBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    GlowCard(Modifier.fillMaxWidth(), glow = Glows.Coral, from = GlowFrom.Top, strength = 0.55f) {
        MonoLabel("Game advisor", icon = Icons.Outlined.SportsEsports, color = ink.onCard)
        Spacer(Modifier.height(10.dp))
        Text("Look at the game", style = MaterialTheme.typography.headlineSmall, color = ink.onCard)
        Text("Ronie reads the board through your glasses and says the move in your ear.", style = MaterialTheme.typography.bodySmall, color = ink.onCard2)
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("chess" to "Chess", "poker" to "Poker", "referee" to "Referee").forEach { (id, label) ->
                Chip(label, activeSubGame == id, { activeSubGame = id }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(14.dp))

        // Look with the glasses camera
        PillAction(if (isEvaluating) "Looking…" else "Look and advise", light = true, loading = isEvaluating,
            icon = Icons.Outlined.CameraAlt, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    isEvaluating = true
                    statusText = "Taking a photo with the glasses…"
                    try {
                        val (jpeg, ageMs) = GlassesCamera.getLatestOrTakePhoto(maxAgeMs = 3500L)
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                        capturedBitmap = bmp
                        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)

                        statusText = "Reading the $activeSubGame position…"
                        val res = brain.callTool("game", buildJsonObject {
                            put("action", "vision")
                            put("image_b64", b64)
                            put("game", activeSubGame)
                        })

                        val err = res["error"]?.jsonPrimitive?.content
                        if (err != null) {
                            statusText = err
                        } else {
                            spokenAdvice = res["spoken"]?.jsonPrimitive?.content
                            statusText = "From a glasses photo (${ageMs} ms old)"
                            if (activeSubGame == "chess") {
                                res["best_move"]?.jsonPrimitive?.content?.let { chessBestMove = it }
                                res["eval"]?.jsonPrimitive?.content?.let { chessEval = it }
                            } else if (activeSubGame == "poker") {
                                res["equity_pct"]?.jsonPrimitive?.content?.let { pokerEquity = it }
                                res["recommendation"]?.jsonPrimitive?.content?.let { pokerRec = it }
                            } else {
                                val verdict = res["description"]?.jsonPrimitive?.content ?: res["spoken"]?.jsonPrimitive?.content
                                if (verdict != null) refereeVerdict = verdict
                            }
                        }
                    } catch (e: Exception) {
                        statusText = "Camera capture failed: ${e.message}"
                    } finally {
                        isEvaluating = false
                    }
                }
            })

        capturedBitmap?.let { bmp ->
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(), contentDescription = "What the glasses saw",
                modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop,
            )
        }

        Spacer(Modifier.height(16.dp))
        MonoLabel("Coaching style")
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("coach" to "Coach", "teach" to "Teach", "referee" to "Referee", "review" to "Review").forEach { (styleId, styleName) ->
                Chip(styleName, coachingStyle == styleId, {
                    coachingStyle = styleId
                    scope.launch {
                        try {
                            brain.callTool("game", buildJsonObject {
                                put("action", "set_style")
                                put("style", styleId)
                            })
                            statusText = "Style set to $styleName"
                        } catch (_: Exception) {}
                    }
                })
            }
        }
        Spacer(Modifier.height(16.dp))

        when (activeSubGame) {
            "chess" -> {
                RField(chessMoveInput, { chessMoveInput = it }, "Opponent's move (e4, Nf3, ghoda f3)")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostPill("Play move", enabled = chessMoveInput.isNotBlank(), modifier = Modifier.weight(1f), onClick = {
                        val input = chessMoveInput.trim()
                        scope.launch {
                            try {
                                val res = brain.callTool("game", buildJsonObject {
                                    put("action", "chess_move")
                                    put("move", input)
                                })
                                val err = res["error"]?.jsonPrimitive?.content
                                if (err != null) {
                                    statusText = "Move error: $err"
                                } else {
                                    statusText = "Played: ${res["move"]?.jsonPrimitive?.content ?: input}"
                                    spokenAdvice = res["spoken"]?.jsonPrimitive?.content
                                    chessMoveInput = ""
                                }
                            } catch (e: Exception) {
                                statusText = "Error pushing move"
                            }
                        }
                    })
                    GhostPill("Best move", modifier = Modifier.weight(1f), onClick = {
                        scope.launch {
                            isEvaluating = true
                            try {
                                val res = brain.callTool("game", buildJsonObject {
                                    put("action", "chess_best")
                                    put("style", coachingStyle)
                                })
                                chessBestMove = res["best_move_san"]?.jsonPrimitive?.content
                                chessEval = res["eval"]?.jsonPrimitive?.content
                                spokenAdvice = res["spoken"]?.jsonPrimitive?.content
                                statusText = "Stockfish eval: ${chessEval ?: "OK"}"
                            } catch (e: Exception) {
                                statusText = "Eval failed"
                            } finally {
                                isEvaluating = false
                            }
                        }
                    })
                    RoundIcon(Icons.Outlined.RestartAlt, "Reset the board", {
                        scope.launch {
                            try {
                                brain.callTool("game", buildJsonObject { put("action", "chess_reset") })
                                chessBestMove = null
                                chessEval = null
                                spokenAdvice = null
                                statusText = "Board reset to the starting position."
                            } catch (_: Exception) {}
                        }
                    })
                }
                chessBestMove?.let { move ->
                    Spacer(Modifier.height(14.dp))
                    BigResult("Best move", move, chessEval?.let { "Eval $it" })
                }
            }
            "poker" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RField(pokerHole, { pokerHole = it }, "Ah Kd", label = "Your cards", modifier = Modifier.weight(1f))
                    RField(pokerBoard, { pokerBoard = it }, "7h 8s 9c", label = "Board", modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RField(pokerPot, { pokerPot = it }, "100", label = "Pot", modifier = Modifier.weight(1f))
                    RField(pokerToCall, { pokerToCall = it }, "20", label = "To call", modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                GhostPill("Work out the odds", modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        isEvaluating = true
                        try {
                            val res = brain.callTool("game", buildJsonObject {
                                put("action", "poker_equity")
                                put("hole", pokerHole.trim())
                                put("board", pokerBoard.trim())
                                put("pot", pokerPot.toDoubleOrNull() ?: 0.0)
                                put("to_call", pokerToCall.toDoubleOrNull() ?: 0.0)
                            })
                            pokerEquity = res["equity_pct"]?.jsonPrimitive?.content
                            pokerRec = res["recommendation"]?.jsonPrimitive?.content
                            spokenAdvice = res["spoken"]?.jsonPrimitive?.content
                            statusText = "Simulated 1,200 hands"
                        } catch (e: Exception) {
                            statusText = "Simulation failed"
                        } finally {
                            isEvaluating = false
                        }
                    }
                })
                pokerEquity?.let { eq ->
                    Spacer(Modifier.height(14.dp))
                    BigResult("Your equity", "${eq.substringBefore('.')}%", pokerRec?.let { "Action: $it" })
                }
            }
            else -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Monopoly", "Uno", "Scrabble", "Catan").forEach { g -> Chip(g, refereeGame == g, { refereeGame = g }) }
                }
                Spacer(Modifier.height(10.dp))
                RField(refereeTopic, { refereeTopic = it }, "What's the argument about? (jail, stacking, robber)")
                Spacer(Modifier.height(10.dp))
                GhostPill("Settle it", modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        try {
                            val res = brain.callTool("game", buildJsonObject {
                                put("action", "rules")
                                put("game", refereeGame.lowercase())
                                put("topic", refereeTopic.trim())
                            })
                            refereeVerdict = res["rule"]?.jsonPrimitive?.content ?: res["spoken"]?.jsonPrimitive?.content
                            spokenAdvice = res["spoken"]?.jsonPrimitive?.content
                            statusText = "Official rule found"
                        } catch (e: Exception) {
                            statusText = "Referee lookup failed"
                        }
                    }
                })
                refereeVerdict?.let { Spacer(Modifier.height(12.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = ink.onCard) }
            }
        }

        spokenAdvice?.let { spoken ->
            Spacer(Modifier.height(14.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = Color.Black.copy(alpha = 0.35f), border = BorderStroke(1.dp, ink.line)) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Headset, contentDescription = "Said in your ear", tint = ink.onCard, modifier = Modifier.size(20.dp))
                    Text(spoken, style = MaterialTheme.typography.bodyMedium, color = ink.onCard)
                }
            }
        }
        statusText?.let { Spacer(Modifier.height(10.dp)); Note(it) }
    }
}

/** A big dot-matrix or text result with a label, e.g. "Best move  Nf3". */
@Composable
private fun BigResult(label: String, value: String, detail: String?) {
    Surface(shape = RoundedCornerShape(22.dp), color = Color.Black.copy(alpha = 0.4f), border = BorderStroke(1.dp, Ronie.ink.line), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MonoLabel(label)
                Text(value, style = MaterialTheme.typography.displaySmall, color = Ronie.ink.onCard)
            }
            detail?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = Ronie.ink.onCard2) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(voices: List<com.ronie.app.brain.Voice>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = voices.firstOrNull { it.current }
    fun describe(v: com.ronie.app.brain.Voice) = "${v.name} · ${v.style} · ${if (v.type == "male") "Male" else "Female"}"

    SectionLabel("Ronie's voice")
    GlowCard(Modifier.fillMaxWidth(), glow = Glows.Ice, from = GlowFrom.Right, strength = 0.35f) {
        if (voices.isEmpty()) {
            Note("Voices load when the brain is online.")
            return@GlowCard
        }
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            RField(
                current?.let(::describe) ?: "Choose a voice", {}, "Choose a voice", readOnly = true, leading = Icons.Outlined.GraphicEq,
                trailing = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Ronie.ink.raised) {
                for ((type, label) in listOf("male" to "Male", "female" to "Female")) {
                    MonoLabel(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    voices.filter { it.type == type }.forEach { v ->
                        DropdownMenuItem(
                            text = { Text("${v.name} · ${v.style}") },
                            trailingIcon = if (v.current) {{ Icon(Icons.Filled.Check, contentDescription = "Current voice") }} else null,
                            onClick = { onSelect(v.name); open = false },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Note("Applies from the next thing you say.")
    }
}

data class MemoryFact(val id: Int, val text: String)

@Composable
fun MemoryScreen(brain: BrainClient) {
    val scope = rememberCoroutineScope()
    val state by brain.state.collectAsState()
    var facts by remember { mutableStateOf<List<MemoryFact>?>(null) }
    var summary by remember { mutableStateOf("") }
    var factInput by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<Int?>(null) } // the memory being corrected, if any
    var editText by remember { mutableStateOf("") }
    val ink = Ronie.ink

    suspend fun load() {
        try {
            val r = brain.request("memory.list", buildJsonObject {})
            facts = r["facts"]?.jsonArray?.map {
                val o = it.jsonObject
                MemoryFact(o["id"]!!.jsonPrimitive.int, o["text"]!!.jsonPrimitive.content)
            } ?: emptyList()
            summary = r["summary"]?.jsonPrimitive?.content.orEmpty()
        } catch (e: Exception) {
            statusMessage = "Couldn't load memories: ${e.message}"
        }
    }
    LaunchedEffect(state) { if (state == BrainState.Connected) load() }

    // The count, big
    GlowCard(Modifier.fillMaxWidth().height(230.dp), glow = Glows.Rose, from = GlowFrom.Center, strength = 0.8f) {
        MonoLabel("Memory", icon = Icons.Outlined.Psychology, color = ink.onCard)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DotNumber(facts?.size?.toString() ?: "-", dot = 7.dp)
            Text("things Ronie knows", style = MaterialTheme.typography.titleMedium, color = ink.onCard.copy(alpha = 0.8f))
        }
        Spacer(Modifier.height(10.dp))
        Text("Shared by every model and every conversation. Stored on your server.", style = MaterialTheme.typography.bodySmall, color = ink.onCard.copy(alpha = 0.7f))
    }

    // What Ronie calls you
    val app = LocalContext.current.applicationContext as RonieApplication
    var nameInput by remember { mutableStateOf(app.userName) }
    GlowCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        SettingRow(Icons.Outlined.Person, "Your name", "How Ronie greets you on Home.")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RField(nameInput, { nameInput = it }, "Your first name", modifier = Modifier.weight(1f))
            GhostPill("Save", { app.userName = nameInput }, enabled = nameInput.trim() != app.userName)
        }
    }

    // Add one
    GlowCard(Modifier.fillMaxWidth()) {
        Text("Remember something", style = MaterialTheme.typography.titleLarge, color = ink.onCard)
        Spacer(Modifier.height(12.dp))
        RField(factInput, { factInput = it }, "I prefer dark roast coffee")
        Spacer(Modifier.height(12.dp))
        PillAction("Remember", light = true, icon = Icons.Outlined.Add, modifier = Modifier.fillMaxWidth(),
            enabled = factInput.isNotBlank() && state == BrainState.Connected, onClick = {
                val text = factInput.trim()
                scope.launch {
                    statusMessage = try {
                        brain.callTool("remember", buildJsonObject { put("text", text) })
                        factInput = ""
                        load()
                        "Remembered."
                    } catch (e: Exception) {
                        "Couldn't save: ${e.message}"
                    }
                }
            })
        statusMessage?.let { Spacer(Modifier.height(8.dp)); Note(it) }
    }

    // What's already saved
    val all = facts
    SectionLabel("Saved" + (all?.let { " · ${it.size}" } ?: ""), "Refresh") { scope.launch { load() } }
    when {
        all == null -> Note(if (state == BrainState.Connected) "Loading…" else "Memories show when the brain is online.")
        all.isEmpty() -> Note("Nothing saved yet. Say \"Ronie, remember…\" or add something above.")
        else -> {
            if (all.size > 5) RField(filter, { filter = it }, "Search memories", leading = Icons.Outlined.Search)
            val shown = all.filter { filter.isBlank() || it.text.contains(filter.trim(), ignoreCase = true) }
            GlowCard(Modifier.fillMaxWidth(), padding = 6.dp) {
                shown.forEachIndexed { i, fact ->
                    if (i > 0) HorizontalDivider(color = ink.line, modifier = Modifier.padding(horizontal = 14.dp))
                    if (editingId == fact.id) {
                        // Correct a memory in place: tap Save, or Cancel to leave it as it was.
                        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                            RField(editText, { editText = it }, fact.text, singleLine = false)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GhostPill("Cancel", { editingId = null }, modifier = Modifier.weight(1f))
                                GhostPill("Save", {
                                    val text = editText.trim()
                                    scope.launch {
                                        statusMessage = try {
                                            val r = brain.request("memory.update", buildJsonObject { put("id", fact.id); put("text", text) })
                                            if (r["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                                                editingId = null
                                                load()
                                                "Updated."
                                            } else "Couldn't update it. It may have just been removed."
                                        } catch (e: Exception) {
                                            "Couldn't update: ${e.message}"
                                        }
                                    }
                                }, modifier = Modifier.weight(1f), enabled = editText.isNotBlank() && editText.trim() != fact.text && state == BrainState.Connected)
                            }
                        }
                        return@forEachIndexed
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(fact.text, style = MaterialTheme.typography.bodyMedium, color = ink.onCard, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                        IconButton(onClick = { editingId = fact.id; editText = fact.text }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit this", tint = ink.onCard3, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = {
                            scope.launch {
                                statusMessage = try {
                                    brain.request("memory.forget", buildJsonObject { put("id", fact.id) })
                                    load()
                                    "Forgotten."
                                } catch (e: Exception) {
                                    "Couldn't forget: ${e.message}"
                                }
                            }
                        }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Forget this", tint = ink.onCard3, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (shown.isEmpty()) Text("No match.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = ink.onCard2)
            }
        }
    }
    if (summary.isNotBlank()) {
        SectionLabel("From your conversations")
        GlowCard(Modifier.fillMaxWidth(), glow = Glows.Rose, from = GlowFrom.BottomLeft, strength = 0.25f) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = ink.onCard2)
        }
    }
}

@Composable
fun ListeningSection(autoListen: Boolean, onAutoListen: (Boolean) -> Unit) {
    val app = LocalContext.current.applicationContext as RonieApplication
    var clear by remember { mutableStateOf(app.clearVoice) }
    GlowCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        SettingRow(Icons.Outlined.Hearing, "Start with my glasses", "Put them on and say \"Hey Ronie\". No tapping.") {
            RSwitch(autoListen, onAutoListen)
        }
        HorizontalDivider(color = Ronie.ink.line, modifier = Modifier.padding(vertical = 10.dp))
        SettingRow(Icons.Outlined.GraphicEq, "Clearer voice",
            if (clear) "Clearer, but you can't talk over it or say \"stop\"." else "Off: talk over Ronie or say \"stop\" any time.") {
            RSwitch(clear, { clear = it; app.clearVoice = it })
        }
    }
}

@Composable
fun LaptopControlSection(brain: BrainClient) {
    var selectedBrowser by rememberSaveable { mutableStateOf("Brave") }
    var note by remember { mutableStateOf<String?>(null) }

    SectionLabel("Laptop")
    GlowCard(Modifier.fillMaxWidth(), glow = Glows.Ice, from = GlowFrom.TopRight, strength = 0.3f) {
        SettingRow(Icons.Outlined.Laptop, "Laptop agent", "Say \"play lofi on my laptop\". Works while the agent runs on the laptop.")
        Spacer(Modifier.height(14.dp))
        MonoLabel("Open things in")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Brave", "Chrome", "Edge", "Firefox").forEach { b ->
                Chip(b, selectedBrowser == b, {
                    selectedBrowser = b
                    brain.updateSettings(buildJsonObject { put("laptop_browser", b.lowercase()) })
                    note = "Ronie will use $b on the laptop."
                }, Modifier.weight(1f))
            }
        }
        note?.let { Spacer(Modifier.height(8.dp)); Note(it) }
    }
}

@Composable
fun KeysScreen(brain: BrainClient, premium: Boolean, onPremium: (Boolean) -> Unit, onSaveKey: (String, String) -> Unit, onRemoveKey: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val state by brain.state.collectAsState()
    var status by remember { mutableStateOf<JsonObject?>(null) }
    val ink = Ronie.ink
    suspend fun load() {
        status = try { brain.request("status.get", buildJsonObject {}) } catch (e: Exception) { null }
    }
    LaunchedEffect(state) { if (state == BrainState.Connected) load() }
    fun refreshSoon() = scope.launch { kotlinx.coroutines.delay(800); load() }

    ScreenTitle("AI", "The models Ronie can use. Keys stay on your server and are never shown again.")

    // The voice model
    val st = status
    val live = st?.get("live_model")?.jsonPrimitive?.content.orEmpty()
    val voiceName = st?.get("live_voice")?.jsonPrimitive?.content.orEmpty()
    val ready = st?.get("providers")?.jsonObject?.get("gemini")?.jsonObject?.get("available")?.jsonPrimitive?.booleanOrNull == true
    GlowCard(Modifier.fillMaxWidth().height(200.dp), glow = Glows.Violet, from = GlowFrom.TopRight, strength = if (ready) 0.9f else 0.4f) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MonoLabel("Voice model", icon = Icons.Outlined.GraphicEq, color = ink.onCard)
            if (st != null) StatusPill(if (ready) "Active" else "Needs Gemini key", ok = ready, warn = !ready)
        }
        Spacer(Modifier.weight(1f))
        Text(live.ifBlank { if (state == BrainState.Connected) "Checking…" else "Offline" }, style = MaterialTheme.typography.headlineMedium, color = ink.onCard,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (voiceName.isNotBlank()) Text("Voice: $voiceName", style = MaterialTheme.typography.bodySmall, color = ink.onCard.copy(alpha = 0.7f))
    }

    GlowCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        SettingRow(Icons.Outlined.WorkspacePremium, "Premium models", "Use Claude, ChatGPT or DeepSeek for thinking when their keys are added.") {
            RSwitch(premium, onPremium)
        }
    }

    SectionLabel("Providers", "Refresh") { scope.launch { load() } }
    GlowCard(Modifier.fillMaxWidth(), padding = 6.dp) {
        KEY_PROVIDERS.forEachIndexed { i, (provider, label) ->
            var open by remember { mutableStateOf(false) }
            var key by remember { mutableStateOf("") }
            var statusNote by remember { mutableStateOf<String?>(null) }
            if (i > 0) HorizontalDivider(color = ink.line, modifier = Modifier.padding(horizontal = 14.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable { open = !open }.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label.substringBefore(" ·"), style = MaterialTheme.typography.titleMedium, color = ink.onCard)
                        Text(label.substringAfter("· "), style = MaterialTheme.typography.bodySmall, color = ink.onCard2)
                    }
                    providerState(status, provider)?.let { (text, ok) -> StatusPill(text, ok) }
                }
                status?.get("providers")?.jsonObject?.get(provider)?.jsonObject?.get("model")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { model ->
                        Spacer(Modifier.height(6.dp))
                        MonoLabel(if (model == "chain") "Auto · best free model first" else model, color = ink.onCard3)
                    }
                AnimatedVisibility(open, enter = fadeIn(tween(250)) + expandVertically(tween(350, easing = EaseOutSoft)), exit = fadeOut(tween(120)) + shrinkVertically(tween(250))) {
                  Column {
                    Spacer(Modifier.height(12.dp))
                    RField(key, { key = it }, "Paste the $provider key", secret = true)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillAction("Save", light = true, enabled = key.isNotBlank(), modifier = Modifier.weight(1f), onClick = {
                            onSaveKey(provider, key.trim())
                            key = ""
                            statusNote = "Saved"
                            refreshSoon()
                        })
                        GhostPill("Remove", onClick = {
                            onRemoveKey(provider)
                            statusNote = "Removed"
                            refreshSoon()
                        })
                    }
                    statusNote?.let { Spacer(Modifier.height(6.dp)); Note(it) }
                  }
                }
            }
        }
    }
}

/** "Active" / "No key" / "Rate limited" for a provider, from the brain's status; null while unknown. */
fun providerState(status: JsonObject?, provider: String): Pair<String, Boolean>? {
    status ?: return null
    status["service_keys"]?.jsonObject?.get(provider)?.jsonPrimitive?.booleanOrNull?.let {
        return if (it) "Active" to true else "No key" to false
    }
    val p = status["providers"]?.jsonObject?.get(provider)?.jsonObject ?: return "Not set up" to false
    val hasKey = p["has_key"]?.jsonPrimitive?.booleanOrNull
    val until = p["limited_until"]?.jsonPrimitive?.doubleOrNull ?: 0.0
    return when {
        hasKey == false -> "No key" to false
        until > System.currentTimeMillis() / 1000.0 -> "Rate limited" to false
        p["available"]?.jsonPrimitive?.booleanOrNull == true -> "Active" to true
        else -> "Not active" to false
    }
}

class CameraActions(
    val connect: () -> Unit,
    val allowNearby: () -> Unit,
    val allowCamera: () -> Unit,
    val updateGlasses: () -> Unit,
)

@Composable
fun GlassesCameraSection(actions: CameraActions) {
    val app = LocalContext.current.applicationContext as RonieApplication
    val registration by GlassesCamera.registration.collectAsState()
    val isPeriodicActive by GlassesCamera.isPeriodicActive.collectAsState()
    val nearbyGranted by GlassesCamera.nearbyGranted.collectAsState()
    var fastCamera by remember { mutableStateOf(app.fastCamera) }
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var message by remember { mutableStateOf("") }
    var needsUpdate by remember { mutableStateOf(false) }
    val registered = registration == RegistrationState.REGISTERED
    val ink = Ronie.ink

    LaunchedEffect(Unit) {
        GlassesCamera.latestPhotoFlow.collect { jpeg ->
            if (jpeg != null) {
                photo = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                message = "Latest photo (${jpeg.size / 1024} KB)"
            }
        }
    }

    SectionLabel("Glasses")
    GlowCard(Modifier.fillMaxWidth(), glow = Glows.Mint, from = GlowFrom.TopRight, strength = if (registered) 0.55f else 0.25f) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Outlined.CameraAlt, bg = Color.Black.copy(alpha = 0.35f))
            StatusPill(if (registered) "Connected" else "Not connected", ok = registered, warn = !registered)
        }
        Spacer(Modifier.height(14.dp))
        Text("Glasses camera", style = MaterialTheme.typography.titleLarge, color = ink.onCard)
        Text("Lets Ronie see what you see, through the Meta AI app.", style = MaterialTheme.typography.bodySmall, color = ink.onCard2)
        Spacer(Modifier.height(14.dp))

        if (!nearbyGranted) {
            Surface(shape = RoundedCornerShape(20.dp), color = Ronie.status.warningContainer) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Allow Nearby devices", style = MaterialTheme.typography.titleSmall, color = Ronie.status.onWarningContainer)
                    Text("Android needs this so Ronie can find the glasses again after Bluetooth drops. Without it the camera stops working after a while.",
                        style = MaterialTheme.typography.bodySmall, color = Ronie.status.onWarningContainer)
                    GhostPill("Allow", actions.allowNearby)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (!registered) {
            PillAction("Connect glasses", actions.connect, Modifier.fillMaxWidth(), light = true, icon = Icons.Outlined.Bluetooth,
                loading = registration == RegistrationState.REGISTERING)
            Spacer(Modifier.height(8.dp))
            Note("In the Meta AI app, turn on Developer Mode first: Settings → App info → tap the version 5 times.")
        } else {
            SettingRow(Icons.Outlined.ShutterSpeed, "Fast camera" + if (isPeriodicActive) " · on now" else "",
                "A photo every 3.5 s while you talk, so \"what am I looking at?\" answers instantly.") {
                RSwitch(fastCamera, { fastCamera = it; app.fastCamera = it })
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostPill("Allow camera", actions.allowCamera, Modifier.weight(1f))
                PillAction("Test photo", light = true, icon = Icons.Outlined.CameraAlt, modifier = Modifier.weight(1f), onClick = {
                    message = "Taking a photo…"
                    scope.launch {
                        message = try {
                            val (jpeg, _) = GlassesCamera.getLatestOrTakePhoto(maxAgeMs = 3500L)
                            photo = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                            needsUpdate = false
                            "Took a photo (${jpeg.size / 1024} KB)"
                        } catch (e: com.ronie.app.glasses.CameraException) {
                            needsUpdate = e.needsGlassesUpdate
                            "Error: ${e.message}"
                        } catch (e: Exception) {
                            "Error: ${e.message}"
                        }
                    }
                })
            }
        }

        if (message.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Note(message) }
        if (needsUpdate) { Spacer(Modifier.height(8.dp)); GhostPill("Update the glasses' software", actions.updateGlasses) }
        photo?.let {
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.Image(
                bitmap = it.asImageBitmap(), contentDescription = "Latest glasses photo",
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.FillWidth,
            )
        }
    }
}

@Composable
fun TvRemoteSection() {
    val app = LocalContext.current.applicationContext as RonieApplication
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(app.tv.pairedHost ?: "") }
    var code by remember { mutableStateOf("") }
    var note by remember {
        mutableStateOf(app.tv.pairedHost?.let { "Paired with the TV at $it." } ?: "Not paired yet.")
    }
    var pairing by remember { mutableStateOf<TvRemote.Pairing?>(null) }
    val paired = app.tv.pairedHost != null
    val ink = Ronie.ink

    fun send(label: String, key: Int) {
        scope.launch {
            note = try {
                app.tv.run(host, listOf(TvRemote.Op.Key(key)))
                "Sent $label"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
    }

    SectionLabel("TV")
    GlowCard(Modifier.fillMaxWidth(), glow = Glows.Violet, from = GlowFrom.BottomLeft, strength = if (paired) 0.55f else 0.25f) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Outlined.Tv, bg = Color.Black.copy(alpha = 0.35f))
            StatusPill(if (paired) "Paired" else "Not paired", ok = paired)
        }
        Spacer(Modifier.height(14.dp))
        Text("Android / Google TV", style = MaterialTheme.typography.titleLarge, color = ink.onCard)
        Text("The phone is the remote, so the TV works even with the laptop off.", style = MaterialTheme.typography.bodySmall, color = ink.onCard2)
        Spacer(Modifier.height(14.dp))

        if (paired) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                RoundIcon(Icons.Outlined.PowerSettingsNew, "Power", { send("Power", 26) }, size = 56.dp)
                RoundIcon(Icons.Outlined.Home, "Home", { send("Home", 3) }, size = 56.dp)
                RoundIcon(Icons.Outlined.PlayArrow, "Play or pause", { send("Play/Pause", 85) }, size = 56.dp, selected = true)
                RoundIcon(Icons.Outlined.VolumeDown, "Volume down", { send("Volume down", 25) }, size = 56.dp)
                RoundIcon(Icons.Outlined.VolumeUp, "Volume up", { send("Volume up", 24) }, size = 56.dp)
            }
            Spacer(Modifier.height(14.dp))
        }

        RField(host, { host = it.trim() }, "192.168.1.20", label = "TV's IP address (Settings → Network)")
        Spacer(Modifier.height(10.dp))
        if (pairing == null) {
            (if (paired) GhostPillFn else LightPillFn)(if (paired) "Pair again" else "Pair with TV", host.isNotBlank()) {
                note = "Connecting to the TV…"
                scope.launch {
                    note = try {
                        pairing = app.tv.startPairing(host)
                        "Type the 6-character code shown on the TV."
                    } catch (e: Exception) {
                        "Failed: ${e.message}"
                    }
                }
            }
        } else {
            RField(code, { code = it.uppercase().take(6) }, "A1B2C3", label = "Code on the TV")
            Spacer(Modifier.height(10.dp))
            PillAction("Confirm", light = true, enabled = code.length == 6, modifier = Modifier.fillMaxWidth(), onClick = {
                val p = pairing ?: return@PillAction
                scope.launch {
                    note = try {
                        p.finish(code)
                        app.reportTv()
                        "Paired. The phone now controls the TV directly."
                    } catch (e: Exception) {
                        "Pairing failed: ${e.message}"
                    }
                    pairing = null
                    code = ""
                }
            })
        }
        Spacer(Modifier.height(8.dp))
        Note(note)
    }
}

private val LightPillFn: @Composable (String, Boolean, () -> Unit) -> Unit = { text, enabled, onClick ->
    PillAction(text, onClick, Modifier.fillMaxWidth(), light = true, enabled = enabled)
}
private val GhostPillFn: @Composable (String, Boolean, () -> Unit) -> Unit = { text, enabled, onClick ->
    GhostPill(text, onClick, Modifier.fillMaxWidth(), enabled = enabled)
}

@Composable
fun BrainSection(state: BrainState, onPair: (String) -> Boolean, runSetup: () -> Unit) {
    val context = LocalContext.current
    var qr by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val ink = Ronie.ink
    val ok = state == BrainState.Connected

    SectionLabel("Brain server")
    GlowCard(Modifier.fillMaxWidth(), glow = if (ok) Glows.Mint else Glows.Coral, from = GlowFrom.Bottom, strength = 0.3f) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Outlined.Dns, bg = Color.Black.copy(alpha = 0.35f))
            StatusPill(when (state) {
                BrainState.Connected -> "Connected"
                BrainState.Connecting -> "Connecting"
                BrainState.Rejected -> "Code not accepted"
                BrainState.Disconnected -> "Offline"
            }, ok = ok, warn = !ok)
        }
        Spacer(Modifier.height(14.dp))
        Text("Your brain", style = MaterialTheme.typography.titleLarge, color = ink.onCard)
        Text(when (state) {
            BrainState.Connected -> "Connected to your server."
            BrainState.Connecting -> "Reaching your server…"
            BrainState.Rejected -> "Your server refused the code. Get a new one with  ronie-brain pairing-code ."
            BrainState.Disconnected -> "Not connected. Retrying…"
        }, style = MaterialTheme.typography.bodySmall, color = ink.onCard2)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostPill("Scan QR", icon = Icons.Outlined.QrCodeScanner, modifier = Modifier.weight(1f), onClick = {
                scanPairingCode(context, { if (!onPair(it)) error = "That isn't a Ronie pairing code." }) { error = "Couldn't open the scanner." }
            })
            GhostPill("Setup guide", icon = Icons.Outlined.MenuBook, modifier = Modifier.weight(1f), onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SETUP_GUIDE)))
            })
        }
        Spacer(Modifier.height(10.dp))
        RField(qr, { qr = it; error = null }, if (ok) "New pairing code (optional)" else "Paste the pairing code", isError = error != null)
        Spacer(Modifier.height(10.dp))
        PillAction("Pair", light = true, enabled = qr.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
            if (onPair(qr)) qr = "" else error = "That isn't a Ronie pairing code."
        })
        error?.let { Spacer(Modifier.height(8.dp)); Note(it, Ronie.status.danger) }
        Spacer(Modifier.height(10.dp))
        Text("Run setup again", style = MaterialTheme.typography.labelLarge, color = ink.onCard2,
            modifier = Modifier.clip(PillShape).clickable(onClick = runSetup).padding(horizontal = 4.dp, vertical = 10.dp))
    }
}

/** "Good morning, Sam" by the phone's clock: morning 5–12, afternoon 12–17, evening 17–22, then late night. */
@Composable
fun greeting(name: String): String {
    var hour by remember { mutableStateOf(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        }
    }
    val part = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Up late"
    }
    return if (name.isBlank()) "$part." else "$part,\n$name."
}

/** "my dog's name is max" → "Your dog's name is max": memories are saved in the user's words; Home speaks to them. */
fun toSecondPerson(fact: String): String {
    var t = " " + fact.trim().removeSuffix(".") + " "
    // Facts the brain picked up itself say "The user …": "The user lives in Pune" → "You live in Pune".
    t = Regex("""(?i) (?:the )?user's """).replace(t, " your ")
    t = Regex("""(?i) (?:the )?user (is|was|has) """).replace(t) { m ->
        " you " + when (m.groupValues[1].lowercase()) { "is" -> "are"; "was" -> "were"; else -> "have" } + " "
    }
    t = Regex("""(?i) (?:the )?user (\w+) """).replace(t) { m ->
        val v = m.groupValues[1]
        val base = when {
            v.endsWith("ies") -> v.dropLast(3) + "y"                                     // studies → study
            Regex("(sh|ch|x|o|ss)es$").containsMatchIn(v) -> v.dropLast(2)              // watches → watch, goes → go
            v.endsWith("s") && !v.endsWith("ss") -> v.dropLast(1)                         // lives → live
            else -> v
        }
        " you $base "
    }
    t = Regex("""(?i) (?:the )?user """).replace(t, " you ")
    listOf(
        " i am " to " you are ", " i'm " to " you're ", " i've " to " you've ", " i'll " to " you'll ", " i was " to " you were ",
        " i " to " you ", " my " to " your ", " me " to " you ", " mine " to " yours ", " myself " to " yourself ",
    ).forEach { (a, b) -> t = t.replace(a, b, ignoreCase = true) }
    return t.trim().replaceFirstChar { it.uppercase() }
}

/**
 * The heart of Home: what Ronie knows about you, one memory at a time, gently cycling. A dot-matrix count of
 * everything it remembers sits on top; tap to open Memory. Loads when the brain is online and every few minutes.
 */
@Composable
fun RonieKnows(brain: BrainClient, state: BrainState, onOpen: () -> Unit) {
    val ink = Ronie.ink
    var facts by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(state) {
        while (state == BrainState.Connected) {
            facts = runCatching {
                brain.request("memory.list", buildJsonObject {})["facts"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    ?.filterNot { it.startsWith("Reminder", ignoreCase = true) } // reminders aren't about you
                    ?.map(::toSecondPerson)
            }.getOrNull() ?: facts
            kotlinx.coroutines.delay(5 * 60_000L)
        }
    }
    val list = facts.orEmpty()
    var i by remember { mutableStateOf(0) }
    LaunchedEffect(list) {
        i = 0
        while (list.size > 1) {
            kotlinx.coroutines.delay(5200) // long enough to type it out and read it
            i = (i + 1) % list.size
        }
    }
    val line = when {
        state != BrainState.Connected && facts == null -> "Your memories show when Ronie is online"
        facts == null -> "Remembering…"
        list.isEmpty() -> "Nothing yet. Say \"Ronie, remember…\""
        else -> list[i % list.size]
    }
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Surface(
        onClick = { haptics.tick(); onOpen() }, shape = RoundedCornerShape(26.dp), color = Color.Black.copy(alpha = 0.32f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)), interactionSource = source,
        modifier = Modifier.pressScale(source, 0.97f).fillMaxWidth(),
    ) {
        // The sky fills the whole card; the label sits on top and the memory types out in the middle of it.
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            MemoryConstellation(count = maxOf(list.size, 5), active = if (list.isEmpty()) -1 else i % list.size,
                modifier = Modifier.matchParentSize())
            MonoLabel(
                "Ronie's brain has identified" + (facts?.let { " · ${it.size}" } ?: ""), icon = Icons.Outlined.Psychology,
                color = ink.onCard.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            )
            Box(
                Modifier.align(Alignment.Center).padding(top = 14.dp).fillMaxWidth().height(104.dp).drawBehind {
                    val c = center
                    val r = size.height * 0.62f
                    withTransform({ scale(2.4f, 1f, c) }) {
                        drawCircle(Brush.radialGradient(listOf(Color(0xFF08080B).copy(alpha = 0.88f), Color(0xFF08080B).copy(alpha = 0.6f), Color.Transparent),
                            center = c, radius = r), r, c)
                    }
                },
            )
            TypedLine(line, Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 22.dp).padding(top = 14.dp))
        }
    }
}

/** One point of the brain mesh: where it sits (x back→front is -1…1 left to right, y top -1 → bottom 1), its depth
 * for the gentle 3D rock, its colour by region, and whether it can hold a memory. */
private class BrainPoint(val x: Float, val y: Float, val z: Float, val color: Color, val cortex: Boolean)

private val CORTEX_OUTLINE = listOf( // side view, the front on the left (like the reference)
    -0.92f to 0.14f, -0.99f to -0.12f, -0.91f to -0.46f, -0.71f to -0.73f, -0.41f to -0.91f, -0.06f to -0.98f,
    0.30f to -0.93f, 0.60f to -0.76f, 0.83f to -0.49f, 0.96f to -0.16f, 0.98f to 0.13f, 0.88f to 0.37f,
    0.62f to 0.44f, 0.36f to 0.41f, 0.12f to 0.54f, -0.20f to 0.62f, -0.46f to 0.55f, -0.61f to 0.38f,
    -0.63f to 0.21f, -0.80f to 0.22f,
)

private fun insideOutline(x: Float, y: Float): Boolean {
    var inside = false
    var j = CORTEX_OUTLINE.size - 1
    for (i in CORTEX_OUTLINE.indices) {
        val (xi, yi) = CORTEX_OUTLINE[i]; val (xj, yj) = CORTEX_OUTLINE[j]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

/** How far (x, y) is inside the side-view outline: 0 at the edge, growing toward the middle. */
private fun depthInside(x: Float, y: Float): Float {
    var best = Float.MAX_VALUE
    for (i in CORTEX_OUTLINE.indices) {
        val (x0, y0) = CORTEX_OUTLINE[i]; val (x1, y1) = CORTEX_OUTLINE[(i + 1) % CORTEX_OUTLINE.size]
        val dx = x1 - x0; val dy = y1 - y0
        val t = (((x - x0) * dx + (y - y0) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        val ex = x0 + t * dx - x; val ey = y0 + t * dy - y
        best = minOf(best, ex * ex + ey * ey)
    }
    return kotlin.math.sqrt(best)
}

/**
 * Builds the brain once, as a 3D shape like the filmed one: two hemispheres, each a domed shell whose width follows
 * how deep a point sits inside the side-view outline (thin at the rim, widest in the middle), a narrow groove where they
 * meet, gentle folds on the surface, a wide cerebellum under the back and a round stem. x = back→front, y = down,
 * z = left/right. Then links each point to its nearest neighbours in 3D.
 */
private fun buildBrain(): Pair<List<BrainPoint>, List<Pair<Int, Int>>> {
    val rnd = java.util.Random(11)
    val pts = mutableListOf<BrainPoint>()
    val yellow = Color(0xFFF7D65A); val orange = Color(0xFFF59E4C); val coral = Color(0xFFFF6A5C)
    val cyan = Color(0xFF4FD1E8); val blue = Color(0xFF6B8BFF)
    fun regionColor(x: Float, y: Float) = when {
        y > 0.22f -> coral                      // temporal lobe
        x < -0.25f -> yellow                    // frontal
        x > 0.62f -> coral.copy(green = 0.55f)  // occipital
        else -> orange                          // parietal
    }
    val groove = 0.035f   // half the gap between the hemispheres
    val half = 0.66f      // a hemisphere's greatest width
    // Top and bottom of the outline at each x, for the cross-section seen from the front.
    val columns = (0..40).associateWith { c ->
        val x = -1f + c * 0.05f
        val ys = (0..200).map { -1f + it * 0.01f }.filter { insideOutline(x, it) }
        if (ys.isEmpty()) null else ys.first() to ys.last()
    }
    // A hemisphere seen from the front (coronal section): curving into the groove at the top, widest a little below
    // the middle, a broad underside; narrower toward the front and back ends; gentle folds on the surface.
    fun width(x: Float, y: Float): Float {
        val (top, bottom) = columns[((x + 1f) / 0.05f).toInt().coerceIn(0, 40)] ?: return 0f
        val v = ((y - top) / (bottom - top).coerceAtLeast(0.05f)).coerceIn(0f, 1f)
        val profile = if (v < 0.6f) 0.32f + 0.68f * kotlin.math.sin(v / 0.6f * 1.5708f).let { Math.pow(it.toDouble(), 0.6).toFloat() }
            else 1f - 0.3f * ((v - 0.6f) / 0.4f).let { it * it }
        val ends = kotlin.math.sqrt((1f - x * x * 0.85f).coerceIn(0.12f, 1f))
        val folds = 1f + 0.05f * kotlin.math.sin(11f * x + 3f * y) * kotlin.math.sin(9f * y - 2f * x)
        return profile * ends * folds
    }
    // the outer (lateral) surface of both hemispheres, a jittered grid over the side outline
    var gy = -1f
    while (gy <= 0.7f) {
        var gx = -1f
        while (gx <= 1f) {
            for (side in listOf(-1f, 1f)) {
                val x = gx + (rnd.nextFloat() - 0.5f) * 0.07f
                val y = gy + (rnd.nextFloat() - 0.5f) * 0.07f
                if (insideOutline(x, y)) pts += BrainPoint(x, y, side * (groove + half * width(x, y)), regionColor(x, y), cortex = true)
            }
            gx += 0.1f
        }
        gy += 0.095f
    }
    // the inner (medial) walls facing each other across the groove, sparser
    gy = -0.95f
    while (gy <= 0.5f) {
        var gx = -0.95f
        while (gx <= 0.95f) {
            for (side in listOf(-1f, 1f)) if (insideOutline(gx, gy) && depthInside(gx, gy) > 0.06f && rnd.nextFloat() < 0.5f)
                pts += BrainPoint(gx, gy, side * groove, regionColor(gx, gy).copy(alpha = 0.35f), cortex = false)
            gx += 0.2f
        }
        gy += 0.2f
    }
    // the dividing groove along the top, a little below the crowns, so from the front it dips between the two halves
    var mx = -0.95f
    while (mx <= 0.95f) {
        val col = columns[((mx + 1f) / 0.05f).toInt().coerceIn(0, 40)]
        if (col != null) for (side in listOf(-1f, 1f)) for (drop in listOf(0.07f, 0.16f)) {
            val y = col.first + drop
            pts += BrainPoint(mx, y, side * (groove + 0.04f * (1 - drop * 3)), regionColor(mx, y).copy(alpha = 0.55f), cortex = false)
        }
        mx += 0.07f
    }
    // the underside: from each hemisphere's broad bottom edge back in to the groove
    var fx = -0.9f
    while (fx <= 0.9f) {
        val col = columns[((fx + 1f) / 0.05f).toInt().coerceIn(0, 40)]
        if (col != null) {
            val y = col.second - 0.02f
            val wide = groove + half * width(fx, y)
            for (side in listOf(-1f, 1f)) for (f in listOf(0.3f, 0.6f))
                pts += BrainPoint(fx, y + 0.02f * f, side * (groove + (wide - groove) * f), regionColor(fx, y), cortex = false)
        }
        fx += 0.1f
    }
    // the cerebellum: a wide, flattened ball under the back
    repeat(70) { k ->
        val yy = 1f - 2f * (k + 0.5f) / 70f; val rr = kotlin.math.sqrt(1f - yy * yy); val a = k * 2.399963f
        pts += BrainPoint(0.6f + kotlin.math.cos(a) * rr * 0.3f, 0.64f + yy * 0.17f, kotlin.math.sin(a) * rr * 0.5f,
            if (k % 3 == 0) blue else cyan, cortex = false)
    }
    // the stem: a short round column
    for (k in 0 until 7) for (a in 0 until 4) {
        val ang = a * 1.5708f + k * 0.6f
        pts += BrainPoint(0.25f + k * 0.012f + kotlin.math.cos(ang) * 0.06f, 0.6f + k * 0.07f, kotlin.math.sin(ang) * 0.08f, blue, cortex = false)
    }

    fun d2(a: Int, b: Int) = (pts[a].x - pts[b].x).let { it * it } + (pts[a].y - pts[b].y).let { it * it } + (pts[a].z - pts[b].z).let { it * it }
    val set = mutableSetOf<Pair<Int, Int>>()
    pts.indices.forEach { a -> pts.indices.filter { it != a }.sortedBy { d2(a, it) }.take(4).forEach { b -> set += minOf(a, b) to maxOf(a, b) } }
    while (true) {
        val seen = BooleanArray(pts.size); val queue = ArrayDeque(listOf(0)); seen[0] = true
        val adj = Array(pts.size) { mutableListOf<Int>() }.also { g -> set.forEach { (a, b) -> g[a] += b; g[b] += a } }
        while (queue.isNotEmpty()) { val n = queue.removeFirst(); adj[n].forEach { if (!seen[it]) { seen[it] = true; queue.addLast(it) } } }
        if (seen.all { it }) break
        val (a, b) = pts.indices.filter { seen[it] }.flatMap { x -> pts.indices.filter { !seen[it] }.map { y -> x to y } }.minBy { (x, y) -> d2(x, y) }
        set += minOf(a, b) to maxOf(a, b)
    }
    return pts to set.toList()
}

/**
 * Ronie's brain: a side-view brain drawn as a mesh of points and links (front on the left, the cerebellum tucked
 * under the back, the stem below), coloured by region and rocking gently in 3D. Some points hold memories. To recall
 * one, a spark runs along the links from the last memory to the next, which then lights up; the line of text arrives
 * with it ([TypedLine] waits for the spark).
 */
@Composable
fun MemoryConstellation(count: Int, active: Int, modifier: Modifier = Modifier) {
    val ink = Ronie.ink
    val (nodes, links) = remember { buildBrain() }
    val total = nodes.size
    val adjacency = remember { Array(total) { mutableListOf<Int>() }.also { g -> links.forEach { (a, b) -> g[a] += b; g[b] += a } } }
    val memoryNode = remember(count) {
        val cortex = nodes.indices.filter { nodes[it].cortex }
        List(count) { k -> cortex[(k * 53 + 7) % cortex.size] } // spread over the cortex
    }
    // The hop-by-hop route between two points along the links (breadth-first).
    fun route(from: Int, to: Int): List<Int> {
        if (from == to) return listOf(to)
        val prev = IntArray(total) { -1 }.also { it[from] = from }
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n == to) break
            adjacency[n].forEach { m -> if (prev[m] < 0) { prev[m] = n; queue.addLast(m) } }
        }
        val path = mutableListOf(to)
        while (path.last() != from && prev[path.last()] >= 0) path += prev[path.last()]
        return path.reversed()
    }

    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(20000, easing = androidx.compose.animation.core.LinearEasing)), label = "a",
    )
    val target = if (active >= 0) memoryNode[active % memoryNode.size] else -1
    var path by remember { mutableStateOf(listOf<Int>()) }
    val travel = remember { Animatable(1f) }   // the spark's progress along the route
    val trail = remember { Animatable(0f) }    // the route's light, fading after arrival
    val arrive = remember { Animatable(1f) }   // the new point's glow, building up while its text types
    var lit by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        if (target < 0) return@LaunchedEffect
        val from = if (lit >= 0) lit else target
        // Reset everything before the new route is drawn, so the old one never flashes.
        travel.snapTo(0f); trail.snapTo(1f); arrive.snapTo(0f)
        path = route(from, target)
        lit = target
        // Always one second, whatever the number of hops, so the text (which waits ~1.1 s) starts as it lands.
        travel.animateTo(1f, tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        launch { trail.animateTo(0f, tween(900)) }
        arrive.animateTo(1f, tween(1400, easing = EaseOutSoft))
    }
    val glow by rememberInfiniteTransition(label = "glow").animateFloat(0.7f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "g")

    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sc = minOf(w * 0.30f, h * 0.36f)
        val cx = w / 2
        val cy = h * 0.56f
        // a full turn about the vertical axis every 20 s
        val yaw = spin
        val cosY = kotlin.math.cos(yaw); val sinY = kotlin.math.sin(yaw)
        val pitch = 0.22f // looking down a little, so the top of the brain shows as it turns
        val cosP = kotlin.math.cos(pitch); val sinP = kotlin.math.sin(pitch)
        val proj = nodes.map { n ->
            val x1 = n.x * cosY + n.z * sinY
            val z1 = -n.x * sinY + n.z * cosY
            val y2 = n.y * cosP + z1 * sinP
            val z2 = -n.y * sinP + z1 * cosP
            val k = 1f / (2.8f - z2) * 2.8f
            floatArrayOf(cx + x1 * sc * 1.12f * k, cy + y2 * sc * k, z2)
        }
        fun at(n: Int) = Offset(proj[n][0], proj[n][1])
        fun depth(n: Int) = ((proj[n][2] + 0.8f) / 1.6f).coerceIn(0f, 1f) // 0 = far, 1 = near

        links.forEach { (a, b) ->
            val d = (depth(a) + depth(b)) / 2
            drawLine(Color(0xFF9DB4D8).copy(alpha = 0.04f + 0.12f * d), at(a), at(b), strokeWidth = (0.4f + 0.4f * d).dp.toPx())
        }
        // the spark's trail along the route
        if (path.size > 1) {
            val segs = path.size - 1
            val pos = travel.value * segs
            for (sIdx in 0 until segs) {
                val f = (pos - sIdx).coerceIn(0f, 1f)
                if (f <= 0f) break
                val a = at(path[sIdx]); val b = at(path[sIdx + 1])
                drawLine(ink.tick.copy(alpha = 0.7f * trail.value), a, a + (b - a) * f, strokeWidth = 1.6.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            if (travel.value < 1f) {
                val sIdx = pos.toInt().coerceAtMost(segs - 1)
                val a = at(path[sIdx]); val b = at(path[sIdx + 1])
                val head = a + (b - a) * (pos - sIdx).coerceIn(0f, 1f)
                drawCircle(Brush.radialGradient(listOf(Color.White, ink.tick.copy(alpha = 0.6f), ink.tick.copy(alpha = 0f)), center = head, radius = 9.dp.toPx()), 9.dp.toPx(), head)
            }
        }
        // points, far ones first, in their region's colour
        val memories = memoryNode.toSet()
        (nodes.indices.filter { proj[it][2] < 0f } + nodes.indices.filter { proj[it][2] >= 0f }).forEach { n -> // far half first
            val d = depth(n)
            val p = at(n)
            if (n == lit && travel.value >= 1f) {
                val g = arrive.value
                val r = (10f + 14f * g).dp.toPx()
                drawCircle(Brush.radialGradient(listOf(ink.tick.copy(alpha = 0.6f * g * glow), Color.Transparent), center = p, radius = r), r, p)
                drawCircle(Color.White, (2.4f + 1.6f * g).dp.toPx(), p)
            } else {
                val size = if (n in memories) 2.0f else 1.0f
                drawCircle(nodes[n].color.copy(alpha = 0.35f + 0.6f * d), (size + 0.7f * d).dp.toPx(), p)
            }
        }
    }
}

/** Types the line out letter by letter; a new line wipes the old one first. */
@Composable
fun TypedLine(text: String, modifier: Modifier = Modifier) {
    val ink = Ronie.ink
    var shown by remember { mutableStateOf(0) }
    var current by remember { mutableStateOf("") }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(text) {
        // the old line fades out gently, then the new one types in as the spark lands
        if (current.isNotEmpty() && shown > 0) fade.animateTo(0f, tween(420, easing = EaseOutSoft))
        current = text
        shown = 0
        fade.snapTo(1f)
        kotlinx.coroutines.delay(680) // with the fade, ~1.1 s: the spark reaches its node first
        while (shown < text.length) {
            kotlinx.coroutines.delay(if (text[shown] == ' ') 18 else 28)
            shown++
        }
    }
    Text(
        current.take(shown),
        style = MaterialTheme.typography.titleLarge.copy(
            shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.9f), blurRadius = 18f), // stays crisp over the cluster
        ),
        color = ink.onCard, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier.graphicsLayer { alpha = fade.value },
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/**
 * The voice line: a wave across the whole card, tapered at both ends. A calm, thin line on standby, a slow swell
 * while waiting for its name, a steady ripple while listening, and full, quick waves while Ronie speaks. Two softer
 * echoes run out of phase behind it. It shows the state, not the actual sound level.
 */
@Composable
fun VoiceLine(status: VoiceStatus, modifier: Modifier = Modifier) {
    val ink = Ronie.ink
    val energy by animateFloatAsState(when (status) {
        VoiceStatus.Speaking -> 1f
        VoiceStatus.Listening -> 0.45f
        VoiceStatus.Waiting, VoiceStatus.Starting -> 0.22f
        else -> 0.05f
    }, tween(700, easing = EaseOutSoft), label = "energy")
    val speed = if (status == VoiceStatus.Speaking) 900 else if (status == VoiceStatus.Listening) 1500 else 4200
    val t by rememberInfiniteTransition(label = "line").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(speed, easing = androidx.compose.animation.core.LinearEasing)), label = "t",
    )
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val mid = size.height / 2
        val amp = size.height * 0.46f * energy
        fun wave(phase: Float, scale: Float, freq: Float): androidx.compose.ui.graphics.Path {
            val path = androidx.compose.ui.graphics.Path()
            var x = 0f
            while (x <= w) {
                val env = kotlin.math.sin(x / w * Math.PI.toFloat()).let { it * it } // tapered ends
                val y = mid + amp * scale * env * (kotlin.math.sin(x / w * freq - t * 2f + phase) * 0.75f + kotlin.math.sin(x / w * freq * 0.45f + t + phase) * 0.25f)
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += 3f
            }
            return path
        }
        val round = androidx.compose.ui.graphics.StrokeCap.Round
        drawPath(wave(2.1f, 0.6f, 14f), ink.live.copy(alpha = 0.35f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx(), cap = round))
        drawPath(wave(4.2f, 0.4f, 22f), Glows.Violet.core.copy(alpha = 0.4f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx(), cap = round))
        drawPath(wave(0f, 1f, 18f), Brush.horizontalGradient(listOf(ink.tick.copy(alpha = 0.2f), ink.tick, ink.onCard, ink.tick, ink.tick.copy(alpha = 0.2f))),
            style = androidx.compose.ui.graphics.drawscope.Stroke(2.2.dp.toPx(), cap = round))
    }
}

/**
 * The voice card's button: a compact centred capsule with an icon and a word, so it reads as a button, not a slider.
 * "Talk to Ronie" is white; while Ronie is on it becomes a glassy "Stop". A soft ring breathes out of it.
 */
@Composable
private fun VoiceButton(label: String, icon: @Composable () -> Unit, light: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ink = Ronie.ink
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val breathe by rememberInfiniteTransition(label = "voiceBtn").animateFloat(0f, 1f, infiniteRepeatable(tween(2000)), label = "b")
    Box(Modifier.height(84.dp), contentAlignment = Alignment.Center) {
        Surface(
            onClick = { haptics.click(); onClick() }, enabled = enabled, shape = PillShape,
            color = if (light) ink.onCard.copy(alpha = if (enabled) 1f else 0.3f) else Color.White.copy(alpha = 0.1f),
            border = if (light) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)), interactionSource = source,
            modifier = Modifier.pressScale(source, 0.94f).height(52.dp).drawBehind {
                if (!enabled) return@drawBehind
                // the breathing ring, a capsule a little larger each beat
                val grow = 12.dp.toPx() * breathe
                drawRoundRect(Color.White.copy(alpha = 0.26f * (1 - breathe)), topLeft = Offset(-grow, -grow),
                    size = androidx.compose.ui.geometry.Size(size.width + grow * 2, size.height + grow * 2),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2 + grow),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            },
        ) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                icon()
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (light) Color(0xFF0A0A0B) else ink.onCard)
            }
        }
    }
}

@Composable
fun TalkButton(onClick: () -> Unit, enabled: Boolean) = VoiceButton("Talk to Ronie", {
    Icon(Icons.Outlined.Mic, contentDescription = null, tint = Color(0xFF0A0A0B), modifier = Modifier.size(20.dp))
}, light = true, enabled = enabled, onClick = onClick)

@Composable
fun StopButton(onClick: () -> Unit) = VoiceButton("Stop", {
    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(Ronie.ink.onCard))
}, light = false, enabled = true, onClick = onClick)

/** A thin ring that fills to the glasses' battery level; dashed and empty when they aren't connected. */
@Composable
fun BatteryRing(percent: Int?, connected: Boolean) {
    val ink = Ronie.ink
    val sweep by animateFloatAsState((percent ?: 0) / 100f, tween(1100, easing = EaseOutSoft), label = "battery")
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            drawArc(ink.onCard.copy(alpha = 0.16f), 0f, 360f, false, Offset(inset, inset), arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, pathEffect = if (connected) null else androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 8f))))
            if (percent != null) drawArc(ink.tick, -90f, 360f * sweep, false, Offset(inset, inset), arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Text(percent?.let { "$it" } ?: "–", style = MaterialTheme.typography.labelLarge, color = ink.onCard)
    }
}

/** One dot per mode; the current one is a lime bar. */
@Composable
fun ModeDots(current: Int) {
    val ink = Ronie.ink
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        MODES.indices.forEach { i ->
            val on = i == current
            val w by animateDpAsState(if (on) 18.dp else 6.dp, spring(dampingRatio = 0.7f, stiffness = 400f), label = "modeDot")
            Box(Modifier.size(width = w, height = 6.dp).clip(CircleShape).background(if (on) ink.tick else ink.onCard.copy(alpha = 0.28f)))
        }
    }
}
