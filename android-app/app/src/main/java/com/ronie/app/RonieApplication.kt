package com.ronie.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.ronie.app.brain.BrainClient
import com.ronie.app.brain.BrainPairing
import com.ronie.app.brain.BrainState
import com.ronie.app.glasses.GlassesCamera
import com.ronie.app.tv.TvRemote
import com.ronie.app.voice.GlassesMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.ronie.app.voice.VoiceController

/** Holds the brain connection and the voice conversation, shared by the screens and the background service. */
class RonieApplication : Application() {
    lateinit var brain: BrainClient
        private set
    lateinit var voice: VoiceController
        private set
    lateinit var glasses: GlassesMonitor
        private set
    lateinit var tv: TvRemote
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        com.ronie.app.voice.Diag.init(this)
        // Meta's Wearables SDK, for the glasses' camera. Voice works without it.
        Wearables.initialize(this).onFailure { error, _ -> Log.w("Ronie", "Wearables SDK didn't start: $error") }
        GlassesCamera.init(this)
        brain = BrainClient(device = Build.MODEL)
        voice = VoiceController(this, brain)
        glasses = GlassesMonitor(this)
        tv = TvRemote(this)
        // The brain sends TV commands here: the phone is the TV remote, so the TV works with the laptop off.
        brain.onMessage = { type, id, payload -> if (type == "tv.remote" && id != null) runTvCommand(id, payload) }
        scope.launch { brain.state.collect { if (it == BrainState.Connected) { reportTv(); reportPresence(force = true) } } }
        watchHome()
        voice.atHome = { lastPresence }
        voice.clearVoice = clearVoice
        voice.setMode(brainMode(mode)) // the mode chosen last time
        voice.onModeRequest = { name -> MODES.firstOrNull { it.equals(name, ignoreCase = true) }?.also { mode = it } }
        // Reconnect to the brain paired last time.
        prefs().getString("pairing", null)?.let(BrainPairing::fromQr)?.let(brain::connect)
    }

    /** Tells the brain whether this phone can control the TV. */
    fun reportTv() {
        runCatching { brain.send("tv.remote.status", JsonObject(mapOf("paired" to JsonPrimitive(tv.pairedHost != null)))) }
    }

    // ---------- at home? ----------

    @Volatile private var lastPresence: Boolean? = null
    private var lastPresenceAt = 0L

    /**
     * Tells the brain whether the user is at home, so Ronie only offers to show things on the TV or laptop when they're
     * nearby. Home = on Wi-Fi and the TV answers on its remote port (it only can on the home network; no location
     * permission needed). Checked when the network changes and every minute.
     */
    private fun watchHome() {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { scope.launch { reportPresence() } }
            override fun onLost(network: Network) { scope.launch { reportPresence() } }
        })
        scope.launch {
            while (true) {
                delay(60_000)
                reportPresence()
            }
        }
    }

    private suspend fun reportPresence(force: Boolean = false) {
        val cm = getSystemService(ConnectivityManager::class.java)
        val wifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val host = tv.pairedHost
        val home = wifi && host != null && withContext(Dispatchers.IO) {
            runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, 6466), 1500) } }.isSuccess
        }
        val now = System.currentTimeMillis()
        if (!force && home == lastPresence && now - lastPresenceAt < 5 * 60_000) return
        if (home != lastPresence) com.ronie.app.voice.Diag.log("presence: ${if (home) "at home" else "away from home"}")
        lastPresence = home
        lastPresenceAt = now
        val sent = brain.state.value == BrainState.Connected &&
            runCatching { brain.send("presence", JsonObject(mapOf("home" to JsonPrimitive(home), "wifi" to JsonPrimitive(wifi)))) }.isSuccess
        if (force || sent) com.ronie.app.voice.Diag.log("presence: ${if (sent) "told the brain" else "not connected"} (home=$home, forced=$force)")
        if (!sent) lastPresenceAt = 0L // try again on the next check
    }

    private fun runTvCommand(id: String, payload: JsonObject) {
        scope.launch {
            val reply = try {
                val host = payload["host"]?.jsonPrimitive?.content?.ifBlank { null } ?: tv.pairedHost.orEmpty()
                val ops = payload["ops"]?.jsonArray.orEmpty().mapNotNull { el ->
                    val o = el.jsonObject
                    o["key"]?.jsonPrimitive?.intOrNull?.let { TvRemote.Op.Key(it) }
                        ?: o["link"]?.jsonPrimitive?.content?.let { TvRemote.Op.Link(it) }
                        ?: o["sleep"]?.jsonPrimitive?.doubleOrNull?.let { TvRemote.Op.Sleep(it) }
                }
                val foreground = tv.run(host, ops)
                val volume = tv.volume
                JsonObject(buildMap {
                    put("foreground", JsonPrimitive(foreground))
                    tv.screenOn?.let { put("screen_on", JsonPrimitive(it)) }
                    if (volume != null) {
                        put("volume", JsonPrimitive(volume.first))
                        put("volume_max", JsonPrimitive(volume.second))
                    }
                })
            } catch (e: Exception) {
                JsonObject(mapOf("error" to JsonPrimitive("TV remote: ${e.message ?: e.javaClass.simpleName}")))
            }
            runCatching { brain.send("tv.remote.result", reply, id) }
        }
    }

    /** Saves a pasted pairing code and connects. False if it isn't a Ronie pairing code. */
    fun pair(code: String): Boolean {
        val pairing = BrainPairing.fromQr(code.trim()) ?: return false
        prefs().edit().putString("pairing", code.trim()).apply()
        brain.connect(pairing)
        return true
    }

    /** The mode chosen in the app or by voice (e.g. "Interview mode"), kept across restarts. */
    var mode: String
        get() = prefs().getString("mode", null)
            ?.let { if (it == "Interview cues") "Interview mode" else it } // the old name
            ?.takeIf { it in MODES } ?: "Assistant"
        set(value) {
            prefs().edit().putString("mode", value).apply()
            _modeFlow.value = value
            voice.setMode(brainMode(value))
        }
    private val _modeFlow by lazy { MutableStateFlow(mode) }
    val modeFlow: StateFlow<String> get() = _modeFlow

    /** Take a photo every 3.5s while in conversation so "what am I looking at?" answers instantly. */
    /** Ronie's replies on the glasses' music link: clearer, but it can't be interrupted. Off by default. */
    var clearVoice: Boolean
        get() = prefs().getBoolean("clear_voice", false)
        set(value) {
            prefs().edit().putBoolean("clear_voice", value).apply()
            voice.clearVoice = value
        }

    var fastCamera: Boolean
        get() = prefs().getBoolean("fast_camera", true)
        set(value) {
            prefs().edit().putBoolean("fast_camera", value).apply()
            if (value && voice.isAwake) {
                com.ronie.app.glasses.GlassesCamera.startPeriodicCapture(3500L)
            } else if (!value) {
                com.ronie.app.glasses.GlassesCamera.stopPeriodicCapture()
            }
        }

    /** Listen for "Ronie" by itself whenever the glasses connect (the "Ronie is ready" background service). */
    var autoListen: Boolean
        get() = prefs().getBoolean("auto_listen", true)
        set(value) = prefs().edit().putBoolean("auto_listen", value).apply()

    private fun prefs() = getSharedPreferences("ronie", MODE_PRIVATE)
}
