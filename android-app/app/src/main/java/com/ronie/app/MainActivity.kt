package com.ronie.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.RegistrationState
import com.ronie.app.brain.BrainClient
import com.ronie.app.brain.BrainState
import com.ronie.app.glasses.GlassesCamera
import com.ronie.app.tv.TvRemote
import com.ronie.app.voice.Battery
import com.ronie.app.voice.GlassesMonitor
import com.ronie.app.ui.MonoLabel
import com.ronie.app.ui.Ronie
import com.ronie.app.ui.RonieTheme
import com.ronie.app.voice.VoiceController
import com.ronie.app.voice.VoiceService
import com.ronie.app.voice.VoiceStatus
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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
    Modes("Modes", Icons.Outlined.Tune),
    Memory("Memory", Icons.Outlined.Psychology),
    Devices("Devices", Icons.Outlined.Devices),
    Keys("AI & Keys", Icons.Outlined.Key)
}

data class ModeCardData(val name: String, val desc: String, val icon: ImageVector)

val APP_MODES = listOf(
    ModeCardData("Assistant", "Hands-free AI, web research, smart laptop & TV controls", Icons.Outlined.AutoAwesome),
    ModeCardData("Interview mode", "Quiet answer points in your ear during a real interview", Icons.Outlined.WorkOutline),
    ModeCardData("Interview practice", "Interactive mock interviews with instant spoken feedback", Icons.Outlined.Psychology),
    ModeCardData("Coding", "Hands-free coding thought partner and architecture assistant", Icons.Outlined.Code),
    ModeCardData("Translator", "Instant bidirectional voice translation between languages", Icons.Outlined.Translate),
    ModeCardData("Game", "Chess Stockfish advice, Texas Hold'em equity & board game referee", Icons.Outlined.SportsEsports),
    ModeCardData("Focus", "Distraction-free thought partner and study coach", Icons.Outlined.SelfImprovement),
    ModeCardData("Cooking", "Hands-free step-by-step recipes, ingredient swaps & timers", Icons.Outlined.Restaurant),
)

val KEY_PROVIDERS = listOf(
    "gemini" to "Gemini (free tier)",
    "groq" to "Groq (free tier)",
    "openrouter" to "OpenRouter (free models)",
    "claude" to "Claude (paid)",
    "openai" to "OpenAI (paid)",
    "deepseek" to "DeepSeek (paid)",
    "tavily" to "Tavily web search (optional)"
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
        if (app.autoListen) startReady()
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
        super.onCreate(savedInstanceState)
        setContent {
            RonieTheme {
                RonieApp(
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
    camera: CameraActions,
) {
    var auto by rememberSaveable { mutableStateOf(autoListen) }
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val mode by savedMode.collectAsState() // also changes when the user switches mode by voice
    var premium by rememberSaveable { mutableStateOf(false) }
    val state by brain.state.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppHeader(state)

            when (tab) {
                Tab.Home -> HomeScreen(brain, state, mode, voice, glasses, onTalk, onOpenModes = { tab = Tab.Modes })
                Tab.Modes -> {
                    ModesScreen(brain, mode) { saveMode(it) }
                    val voices by brain.voices.collectAsState()
                    VoiceScreen(voices, onSelect = brain::setVoice)
                }
                Tab.Memory -> MemoryScreen(brain)
                Tab.Devices -> {
                    AutoListenSection(auto) {
                        auto = it
                        setAutoListen(it)
                    }
                    VoiceStyleSection()
                    GlassesCameraSection(camera)
                    TvRemoteSection()
                    LaptopControlSection(brain)
                    DevicesScreen(state, pair)
                }
                Tab.Keys -> KeysScreen(brain, premium, onPremium = {
                    premium = it
                    brain.updateSettings(buildJsonObject { put("premium", it) })
                }, onSaveKey = brain::setKey, onRemoveKey = brain::deleteKey)
            }
        }
    }
}

@Composable
fun AppHeader(brainState: BrainState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Ronie", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
            MonoLabel("R.O.N.I.E // Ray-Ban Meta")
        }
        val (dot, label) = when (brainState) {
            BrainState.Connected -> Ronie.status.success to "Online"
            BrainState.Connecting -> Ronie.status.warning to "Connecting"
            BrainState.Rejected -> Ronie.status.danger to "Pair again"
            BrainState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant to "Offline"
        }
        Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun HomeScreen(
    brain: BrainClient, state: BrainState, mode: String, voice: VoiceController, glasses: GlassesMonitor, onTalk: () -> Unit,
    onOpenModes: () -> Unit,
) {
    val glassesName by glasses.name.collectAsState()
    val status by voice.status.collectAsState()
    val detail by voice.detail.collectAsState()
    val lines by voice.lines.collectAsState()
    val context = LocalContext.current
    val glassesBattery = remember(glassesName) { glassesName?.let { Battery.glasses(context)?.second } ?: -1 }

    // 1. Status glance cards
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Glasses Card
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, if (glassesName != null) Ronie.status.success.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (glassesName != null) Icons.Outlined.Headset else Icons.Outlined.Bluetooth,
                        contentDescription = null,
                        tint = if (glassesName != null) Ronie.status.success else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    if (glassesBattery >= 0) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (glassesBattery > 30) Ronie.status.successContainer else Ronie.status.dangerContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                val tint = if (glassesBattery > 30) Ronie.status.onSuccessContainer else Ronie.status.onDangerContainer
                                Icon(Icons.Outlined.BatteryFull, contentDescription = "Glasses battery", tint = tint, modifier = Modifier.size(14.dp))
                                Text("$glassesBattery%", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = tint)
                            }
                        }
                    }
                }
                Text(
                    text = glassesName ?: "Phone Audio",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                Text(
                    text = if (glassesName != null) "Glasses Mic & Audio" else "Phone Mic/Speaker",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Mode card: tap to change the mode
        Card(
            onClick = onOpenModes,
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = mode,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Mode · tap to change", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // 2. Voice hero: the site's dark "Hello Stranger" card
    val talking = status != VoiceStatus.Off
    val live = status == VoiceStatus.Listening || status == VoiceStatus.Speaking
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (live) 1.06f else 1f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "hero_scale",
    )
    val ink = Ronie.ink
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = talking || state == BrainState.Connected, onClick = onTalk),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ink.card),
        border = BorderStroke(1.dp, if (live) ink.live.copy(alpha = 0.6f) else ink.line),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                MonoLabel(
                    "Voice // " + when (status) {
                        VoiceStatus.Off -> "Standby"
                        VoiceStatus.Starting -> "Starting"
                        VoiceStatus.Waiting -> "Waiting for name"
                        VoiceStatus.Listening -> "Listening"
                        VoiceStatus.Speaking -> "Speaking"
                        VoiceStatus.Error -> "Error"
                    },
                    icon = Icons.Outlined.GraphicEq,
                    color = if (live) ink.live else ink.onCard2,
                )
                if (talking) {
                    Surface(
                        onClick = onTalk,
                        shape = RoundedCornerShape(999.dp),
                        color = ink.line,
                        modifier = Modifier.heightIn(min = 36.dp),
                    ) {
                        Text("STOP", modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelSmall, color = ink.onCard)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            when (status) {
                                VoiceStatus.Listening, VoiceStatus.Speaking -> ink.live
                                VoiceStatus.Error -> Ronie.status.danger
                                else -> ink.line
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (status == VoiceStatus.Starting) {
                        CircularProgressIndicator(modifier = Modifier.size(26.dp), color = ink.onCard, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = when (status) {
                                VoiceStatus.Speaking -> Icons.AutoMirrored.Filled.VolumeUp
                                VoiceStatus.Waiting -> Icons.Outlined.Hearing
                                VoiceStatus.Error -> Icons.Filled.MicOff
                                else -> Icons.Filled.Mic
                            },
                            contentDescription = if (talking) "Stop talking" else "Talk to Ronie",
                            tint = if (live) ink.card else ink.onCard,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = when (status) {
                            VoiceStatus.Off -> if (state == BrainState.Connected) "Tap to talk" else "Connecting…"
                            VoiceStatus.Starting -> "Getting ready"
                            VoiceStatus.Waiting -> "Say \"Ronie\""
                            VoiceStatus.Listening -> "Listening"
                            VoiceStatus.Speaking -> "Speaking"
                            VoiceStatus.Error -> "Something went wrong"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = ink.onCard,
                    )
                    Text(
                        text = when (status) {
                            VoiceStatus.Off -> "Or say \"Hey Ronie\" with your glasses on"
                            VoiceStatus.Starting -> detail.ifBlank { "Connecting the voice" }
                            VoiceStatus.Waiting -> "Ronie wakes when you say its name"
                            VoiceStatus.Listening -> when {
                                detail.isNotBlank() && detail != "Listening" -> detail
                                glassesName != null -> "Through your glasses"
                                else -> "Through the phone"
                            }
                            VoiceStatus.Speaking -> "Tap to interrupt"
                            VoiceStatus.Error -> detail
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink.onCard2,
                    )
                }
            }
        }
    }

    // 5. Live Conversation Feed
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Forum, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                MonoLabel("Live conversation")
            }
            if (lines.isNotEmpty()) {
                Text(
                    "${lines.size} messages",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (lines.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Things to try", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    PromptSuggestionItem(Icons.Outlined.Visibility, "\"What am I looking at?\"", "Takes a photo with your glasses")
                    PromptSuggestionItem(Icons.Outlined.Tv, "\"Play Stranger Things on the TV\"", "Plays it on your TV")
                    PromptSuggestionItem(Icons.Outlined.Laptop, "\"Play lofi music on my laptop\"", "Plays it in Brave on your laptop")
                    PromptSuggestionItem(Icons.Outlined.BatteryFull, "\"How much battery do my glasses have?\"", "Glasses and phone battery")
                    PromptSuggestionItem(Icons.Outlined.TravelExplore, "\"What was today's F1 result?\"", "Searches the web and sums it up")
                }
            }
        } else {
            lines.takeLast(10).asReversed().forEach { line ->  // newest first, no scrolling needed
                when (line.who) {
                    "cue" -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Ronie.status.warningContainer.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, Ronie.status.warning)
                        ) {
                            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = Ronie.status.warning, modifier = Modifier.size(20.dp))
                                Column {
                                    Text("Interview", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = Ronie.status.onWarningContainer)
                                    Text(line.text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                }
                            }
                        }
                    }
                    "you" -> {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Surface(
                                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.widthIn(max = 290.dp)
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Text("You", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                                    Text(line.text, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    "room" -> {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                "Heard: ${line.text}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            Surface(
                                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.widthIn(max = 290.dp)
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                        Text("Ronie", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                                    }
                                    Text(line.text, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PromptSuggestionItem(icon: ImageVector, prompt: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(prompt, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ModesScreen(brain: BrainClient, current: String, onSelect: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var jobDescription by remember { mutableStateOf("") }
    var jobStatus by remember { mutableStateOf<String?>(null) }

    Text("Modes", style = MaterialTheme.typography.titleLarge)
    Text("Pick what Ronie helps with. Its settings appear below.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        APP_MODES.forEach { m ->
            val isSelected = m.name == current
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(m.name) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(
                    if (isSelected) 1.5.dp else 1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            m.icon,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(m.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isSelected) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    // Settings for the chosen mode only.
    if (current == "Game") GameModeCard(brain)

    if (current.startsWith("Interview")) Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.WorkOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Interview Job Description", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
            Text("Paste the job description for the role you're interviewing for.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = jobDescription,
                onValueChange = { jobDescription = it },
                placeholder = { Text("Paste job description requirements & responsibilities…") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Button(
                onClick = {
                    scope.launch {
                        jobStatus = "Saving…"
                        jobStatus = try {
                            brain.request("interview.job", buildJsonObject { put("text", jobDescription) })
                            "Saved. Ronie will use it for interview answers."
                        } catch (e: Exception) {
                            "Couldn't save: ${e.message}"
                        }
                    }
                },
                enabled = jobDescription.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Save Job Description")
            }

            jobStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun GameModeCard(brain: BrainClient) {
    val scope = rememberCoroutineScope()
    var activeSubGame by remember { mutableStateOf("chess") } // chess, poker, referee
    var coachingStyle by remember { mutableStateOf("coach") } // coach, teach, referee, review
    var statusText by remember { mutableStateOf<String?>(null) }
    var spokenAdvice by remember { mutableStateOf<String?>(null) }
    var isEvaluating by remember { mutableStateOf(false) }

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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.SportsEsports, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Game Mode Advisor", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                }
                Text("Stockfish + Odds", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }

            Text("Hands-free ear advice for Chess, Poker & Board Games.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Look with Glasses Camera button
            Button(
                onClick = {
                    scope.launch {
                        isEvaluating = true
                        statusText = "Capturing glasses photo..."
                        try {
                            val (jpeg, ageMs) = GlassesCamera.getLatestOrTakePhoto(maxAgeMs = 3500L)
                            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                            capturedBitmap = bmp
                            val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)

                            statusText = "Analyzing $activeSubGame state with vision..."
                            val res = brain.callTool("game", buildJsonObject {
                                put("action", "vision")
                                put("image_b64", b64)
                                put("game", activeSubGame)
                            })

                            val err = res["error"]?.jsonPrimitive?.content
                            if (err != null) {
                                statusText = err
                            } else {
                                val spoken = res["spoken"]?.jsonPrimitive?.content
                                spokenAdvice = spoken
                                statusText = "Game analyzed from glasses photo (${ageMs}ms ago)"

                                if (activeSubGame == "chess") {
                                    val move = res["best_move"]?.jsonPrimitive?.content
                                    val eval = res["eval"]?.jsonPrimitive?.content
                                    if (move != null) chessBestMove = move
                                    if (eval != null) chessEval = eval
                                } else if (activeSubGame == "poker") {
                                    val eq = res["equity_pct"]?.jsonPrimitive?.content
                                    val rec = res["recommendation"]?.jsonPrimitive?.content
                                    if (eq != null) pokerEquity = eq
                                    if (rec != null) pokerRec = rec
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
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (isEvaluating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Looking at Board & Calculating Move…", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                } else {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Look at Game with Glasses (Play Next Move)", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                }
            }

            capturedBitmap?.let { bmp ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().height(140.dp)
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Glasses Game View",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                }
            }

            // Sub-game selector (Chess, Poker, Rules Referee)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("chess" to "Chess", "poker" to "Poker", "referee" to "Referee").forEach { (id, label) ->
                    val isSel = activeSubGame == id
                    FilledTonalButton(
                        onClick = { activeSubGame = id },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }

            // Coaching style selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Coaching Style", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("coach" to "Coach", "teach" to "Teach", "referee" to "Referee", "review" to "Review").forEach { (styleId, styleName) ->
                        val isStyleSel = coachingStyle == styleId
                        FilterChip(
                            selected = isStyleSel,
                            onClick = {
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
                            },
                            label = { Text(styleName, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            // Subgame: Chess
            if (activeSubGame == "chess") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = chessMoveInput,
                            onValueChange = { chessMoveInput = it },
                            placeholder = { Text("Move (e.g. e4, Nf3, ghoda f3)") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                val input = chessMoveInput.trim()
                                if (input.isNotBlank()) {
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
                                                val moveMade = res["move"]?.jsonPrimitive?.content ?: input
                                                statusText = "Played: $moveMade"
                                                spokenAdvice = res["spoken"]?.jsonPrimitive?.content
                                                chessMoveInput = ""
                                            }
                                        } catch (e: Exception) {
                                            statusText = "Error pushing move"
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Push")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
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
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isEvaluating) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            } else {
                                Text("Best Move")
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        brain.callTool("game", buildJsonObject { put("action", "chess_reset") })
                                        chessBestMove = null
                                        chessEval = null
                                        spokenAdvice = null
                                        statusText = "Board reset to starting position."
                                    } catch (_: Exception) {}
                                }
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Reset")
                        }
                    }

                    if (chessBestMove != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Suggested Move", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(chessBestMove ?: "", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                                }
                                chessEval?.let {
                                    Text("Eval: $it", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                    }
                }
            }

            // Subgame: Poker
            if (activeSubGame == "poker") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = pokerHole,
                            onValueChange = { pokerHole = it },
                            label = { Text("Hole Cards") },
                            placeholder = { Text("Ah Kd or pocket aces") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = pokerBoard,
                            onValueChange = { pokerBoard = it },
                            label = { Text("Board Cards") },
                            placeholder = { Text("7h 8s 9c") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = pokerPot,
                            onValueChange = { pokerPot = it },
                            label = { Text("Pot Size") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = pokerToCall,
                            onValueChange = { pokerToCall = it },
                            label = { Text("To Call") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                    }

                    Button(
                        onClick = {
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
                                    statusText = "Calculated across 1,200 iterations"
                                } catch (e: Exception) {
                                    statusText = "Simulation failed"
                                } finally {
                                    isEvaluating = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isEvaluating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        } else {
                            Text("Simulate Equity & Pot Odds")
                        }
                    }

                    if (pokerEquity != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Hand Equity: $pokerEquity%", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                                    Text("Action: ${pokerRec ?: ""}", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                    }
                }
            }

            // Subgame: Rules Referee
            if (activeSubGame == "referee") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Monopoly", "Uno", "Scrabble", "Catan").forEach { g ->
                            FilterChip(
                                selected = refereeGame == g,
                                onClick = { refereeGame = g },
                                label = { Text(g, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = refereeTopic,
                        onValueChange = { refereeTopic = it },
                        placeholder = { Text("Dispute topic (e.g. jail, stacking, robber)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    val res = brain.callTool("game", buildJsonObject {
                                        put("action", "rules")
                                        put("game", refereeGame.lowercase())
                                        put("topic", refereeTopic.trim())
                                    })
                                    refereeVerdict = res["rule"]?.jsonPrimitive?.content ?: res["spoken"]?.jsonPrimitive?.content
                                    spokenAdvice = res["spoken"]?.jsonPrimitive?.content
                                    statusText = "Official rule retrieved"
                                } catch (e: Exception) {
                                    statusText = "Referee lookup failed"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Settle Dispute (Referee)")
                    }

                    if (refereeVerdict != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                refereeVerdict ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }

            // Spoken Advice Callout
            spokenAdvice?.let { spoken ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Headset, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(
                            spoken,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            statusText?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(voices: List<com.ronie.app.brain.Voice>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = voices.firstOrNull { it.current }
    fun describe(v: com.ronie.app.brain.Voice) = "${v.name} · ${v.style} · ${if (v.type == "male") "Male" else "Female"}"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MonoLabel("Ronie's voice", icon = Icons.Outlined.GraphicEq)
            if (voices.isEmpty()) {
                Text("Voices load when the brain is online.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                OutlinedTextField(
                    value = current?.let(::describe) ?: "Choose a voice",
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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
            Text("Applies from the next thing you say.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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

    Text("Memory", style = MaterialTheme.typography.titleLarge)
    Text("What Ronie remembers about you. Every model and voice session uses it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    // Add a fact
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MonoLabel("Remember something", icon = Icons.Outlined.AutoAwesome)
            OutlinedTextField(
                value = factInput,
                onValueChange = { factInput = it },
                placeholder = { Text("e.g. I prefer dark roast coffee") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Button(
                onClick = {
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
                },
                enabled = factInput.isNotBlank() && state == BrainState.Connected,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(999.dp)
            ) {
                Text("Remember")
            }
        }
    }

    statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

    // What's already saved
    val all = facts
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        MonoLabel("Saved" + (all?.let { " · ${it.size}" } ?: ""), icon = Icons.Outlined.Psychology)
        TextButton(onClick = { scope.launch { load() } }, enabled = state == BrainState.Connected) { Text("Refresh") }
    }
    when {
        all == null -> Text(
            if (state == BrainState.Connected) "Loading…" else "Memories show when the brain is online.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        all.isEmpty() -> Text("Nothing saved yet. Say \"Ronie, remember…\" or add something above.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {
            if (all.size > 5) OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Search memories") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            val shown = all.filter { filter.isBlank() || it.text.contains(filter.trim(), ignoreCase = true) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column {
                    shown.forEachIndexed { i, fact ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(fact.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
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
                                Icon(Icons.Outlined.Delete, contentDescription = "Forget this", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (shown.isEmpty()) Text("No match.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (summary.isNotBlank()) {
        MonoLabel("From your conversations", icon = Icons.Outlined.Forum)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Text(summary, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun VoiceStyleSection() {
    val app = LocalContext.current.applicationContext as RonieApplication
    var clear by remember { mutableStateOf(app.clearVoice) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Clearer voice", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (clear) "Ronie sounds clearer, but you can't talk over it or say \"stop\"."
                    else "Off: talk over Ronie or say \"stop\" and it stops and listens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = clear, onCheckedChange = { clear = it; app.clearVoice = it })
        }
    }
}

@Composable
fun AutoListenSection(autoListen: Boolean, onAutoListen: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Hearing,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text("Start with my glasses", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Text(
                    "Put on glasses and say \"Hey Ronie\" — no tapping needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = autoListen, onCheckedChange = onAutoListen)
        }
    }
}

@Composable
fun LaptopControlSection(brain: BrainClient) {
    var selectedBrowser by rememberSaveable { mutableStateOf("Brave") }
    var note by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Laptop, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Laptop", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Say \"play lofi on my laptop\" or \"open YouTube\". Works while the Ronie agent runs on the laptop.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Open things in", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Brave", "Chrome", "Edge", "Firefox").forEach { b ->
                    FilterChip(
                        selected = selectedBrowser == b,
                        onClick = {
                            selectedBrowser = b
                            brain.updateSettings(buildJsonObject { put("laptop_browser", b.lowercase()) })
                            note = "Ronie will use $b on the laptop."
                        },
                        label = { Text(b) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun KeysScreen(brain: BrainClient, premium: Boolean, onPremium: (Boolean) -> Unit, onSaveKey: (String, String) -> Unit, onRemoveKey: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val state by brain.state.collectAsState()
    var status by remember { mutableStateOf<JsonObject?>(null) }
    suspend fun load() {
        status = try { brain.request("status.get", buildJsonObject {}) } catch (e: Exception) { null }
    }
    LaunchedEffect(state) { if (state == BrainState.Connected) load() }
    fun refreshSoon() = scope.launch { kotlinx.coroutines.delay(800); load() }

    Text("AI & Keys", style = MaterialTheme.typography.titleLarge)
    Text("Which models Ronie can use right now. Keys stay on the brain server and are never shown.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    // The voice model
    status?.let { st ->
        val live = st["live_model"]?.jsonPrimitive?.content.orEmpty()
        val voiceName = st["live_voice"]?.jsonPrimitive?.content.orEmpty()
        val gemini = st["providers"]?.jsonObject?.get("gemini")?.jsonObject
        val ready = gemini?.get("available")?.jsonPrimitive?.booleanOrNull == true
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Ronie.ink.card),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    MonoLabel("Voice model", icon = Icons.Outlined.GraphicEq, color = Ronie.ink.onCard2)
                    StatusPill(if (ready) "Active" else "Needs Gemini key", ok = ready)
                }
                Text(live, style = MaterialTheme.typography.titleMedium, color = Ronie.ink.onCard)
                Text("Voice: $voiceName", style = MaterialTheme.typography.bodySmall, color = Ronie.ink.onCard2)
            }
        }
    }

    // Premium Mode Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text("Premium Mode", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Text("Use paid Claude, OpenAI, or DeepSeek models when API keys are configured.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = premium, onCheckedChange = onPremium)
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        MonoLabel("Providers")
        TextButton(onClick = { scope.launch { load() } }, enabled = state == BrainState.Connected) { Text("Refresh") }
    }
    if (status == null) Text(
        if (state == BrainState.Connected) "Checking…" else "Status shows when the brain is online.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    KEY_PROVIDERS.forEach { (provider, label) ->
        var key by remember { mutableStateOf("") }
        var statusNote by remember { mutableStateOf<String?>(null) }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(provider.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    providerState(status, provider)?.let { (text, ok) -> StatusPill(text, ok) }
                }
                status?.get("providers")?.jsonObject?.get(provider)?.jsonObject?.get("model")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { model ->
                        MonoLabel("Model // " + if (model == "chain") "auto (best free model first)" else model)
                    }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        placeholder = { Text("Paste $provider key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Button(
                        enabled = key.isNotBlank(),
                        onClick = {
                            onSaveKey(provider, key.trim())
                            key = ""
                            statusNote = "Saved"
                            refreshSoon()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save")
                    }
                    FilledTonalButton(
                        onClick = {
                            onRemoveKey(provider)
                            statusNote = "Removed"
                            refreshSoon()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Clear")
                    }
                }
                statusNote?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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

@Composable
fun StatusPill(text: String, ok: Boolean) {
    val bg = if (ok) Ronie.status.successContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (ok) Ronie.status.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(999.dp), color = bg) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (ok) Ronie.status.success else fg))
            Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = fg)
        }
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

    LaunchedEffect(Unit) {
        GlassesCamera.latestPhotoFlow.collect { jpeg ->
            if (jpeg != null) {
                photo = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                message = "Live Snapshot Buffer (${jpeg.size / 1024} KB)"
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Glasses Camera", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (registration == RegistrationState.REGISTERED) Ronie.status.successContainer else Ronie.status.warningContainer
                ) {
                    Text(
                        if (registration == RegistrationState.REGISTERED) "Meta AI Paired ✓" else "Not Registered",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (registration == RegistrationState.REGISTERED) Ronie.status.onSuccessContainer else Ronie.status.onWarningContainer
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Fast Camera (every 3.5s)", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                            if (isPeriodicActive) {
                                Surface(shape = RoundedCornerShape(6.dp), color = Ronie.status.successContainer) {
                                    Text("ACTIVE", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = Ronie.status.onSuccessContainer)
                                }
                            }
                        }
                        Text(
                            "Snaps every 3.5s in conversation for instant \"What am I looking at?\" answers",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = fastCamera,
                        onCheckedChange = {
                            fastCamera = it
                            app.fastCamera = it
                        }
                    )
                }
            }

            if (!nearbyGranted) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Ronie.status.warningContainer
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "Nearby Devices permission needed",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = Ronie.status.onWarningContainer
                        )
                        Text(
                            "Android needs this so Ronie can find the glasses again after the Bluetooth link drops. Without it the camera stops working after a while.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ronie.status.onWarningContainer
                        )
                        Button(onClick = actions.allowNearby, shape = RoundedCornerShape(10.dp)) {
                            Text("Allow Nearby Devices")
                        }
                    }
                }
            }

            if (registration != RegistrationState.REGISTERED) {
                Button(
                    onClick = actions.connect,
                    enabled = registration != RegistrationState.REGISTERING,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Connect Ronie to Meta AI App")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = actions.allowCamera,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Allow Camera")
                    }
                    Button(
                        onClick = {
                            message = "Snapping photo…"
                            scope.launch {
                                message = try {
                                    val (jpeg, _) = GlassesCamera.getLatestOrTakePhoto(maxAgeMs = 3500L)
                                    photo = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                                    needsUpdate = false
                                    "Captured photo (${jpeg.size / 1024} KB)"
                                } catch (e: com.ronie.app.glasses.CameraException) {
                                    needsUpdate = e.needsGlassesUpdate
                                    "Error: ${e.message}"
                                } catch (e: Exception) {
                                    "Error: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Test Photo")
                    }
                }
            }

            if (message.isNotEmpty()) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (needsUpdate) {
                Button(onClick = actions.updateGlasses, shape = RoundedCornerShape(12.dp)) { Text("Update Glasses Software") }
            }

            photo?.let {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Glasses Camera Snapshot",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
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
        mutableStateOf(app.tv.pairedHost?.let { "Paired with TV at $it" } ?: "Not paired yet.")
    }
    var pairing by remember { mutableStateOf<TvRemote.Pairing?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Smart TV Remote", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }

            Text(
                "Phone acts as the TV's direct remote; commands work even with laptop powered off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("TV IP address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            // Quick TV Remote Action Pad if paired
            if (app.tv.pairedHost != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                try {
                                    app.tv.run(host, listOf(TvRemote.Op.Key(26)))
                                    note = "Sent Power toggle"
                                } catch (e: Exception) {
                                    note = "Error: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.PowerSettingsNew, contentDescription = "Power", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Power")
                    }

                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                try {
                                    app.tv.run(host, listOf(TvRemote.Op.Key(3)))
                                    note = "Sent Home"
                                } catch (e: Exception) {
                                    note = "Error: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.Home, contentDescription = "Home", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Home")
                    }

                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                try {
                                    app.tv.run(host, listOf(TvRemote.Op.Key(85)))
                                    note = "Sent Play/Pause"
                                } catch (e: Exception) {
                                    note = "Error: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play/Pause", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Play")
                    }
                }
            }

            if (pairing == null) {
                Button(
                    enabled = host.isNotBlank(),
                    onClick = {
                        note = "Connecting to TV…"
                        scope.launch {
                            note = try {
                                pairing = app.tv.startPairing(host)
                                "Enter the 6-character code shown on the TV."
                            } catch (e: Exception) {
                                "Failed: ${e.message}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (app.tv.pairedHost == null) "Pair with TV" else "Re-Pair with TV")
                }
            } else {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().take(6) },
                    label = { Text("6-Character Code from TV") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Button(
                    enabled = code.length == 6,
                    onClick = {
                        val p = pairing ?: return@Button
                        scope.launch {
                            note = try {
                                p.finish(code)
                                app.reportTv()
                                "Paired successfully. Phone controls TV directly."
                            } catch (e: Exception) {
                                "Pairing failed: ${e.message}"
                            }
                            pairing = null
                            code = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Confirm Pairing Code")
                }
            }

            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DevicesScreen(state: BrainState, onPair: (String) -> Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Brain Server Pairing", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }

            Text(
                when (state) {
                    BrainState.Connected -> "Connected to Ronie cloud brain."
                    BrainState.Connecting -> "Connecting to server…"
                    BrainState.Rejected -> "Pairing code not accepted; paste a new one."
                    BrainState.Disconnected -> "Not connected; retrying…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            var qr by remember { mutableStateOf("") }
            var error by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = qr,
                onValueChange = {
                    qr = it
                    error = false
                },
                label = { Text(if (state == BrainState.Connected) "New pairing code (optional)" else "Pairing code") },
                isError = error,
                supportingText = { if (error) Text("Invalid pairing code format") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Button(
                enabled = qr.isNotBlank(),
                onClick = {
                    if (onPair(qr)) qr = "" else error = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Pair Brain Server")
            }
        }
    }
}
