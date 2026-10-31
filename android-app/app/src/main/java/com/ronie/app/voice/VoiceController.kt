package com.ronie.app.voice

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import com.ronie.app.RonieApplication
import com.ronie.app.brain.BrainClient
import com.ronie.app.brain.BrainException
import com.ronie.app.glasses.GlassesCamera
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

enum class VoiceStatus { Off, Starting, Waiting, Listening, Speaking, Error }

data class Line(val who: String, val text: String)

/**
 * Runs Ronie's voice conversation on the phone: asks the brain for a one-use Live token, opens a
 * session, and starts a fresh one whenever a session ends or the voice changes, so it feels continuous.
 */
class VoiceController(private val context: Context, private val brain: BrainClient) {
    private val route = AudioRoute(context)

    /** Clearer voice on the glasses' music link, but Ronie can't be interrupted (its mic is off meanwhile). */
    var clearVoice: Boolean
        get() = route.highQuality
        set(value) { route.highQuality = value }
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(VoiceStatus.Off)
    val status: StateFlow<VoiceStatus> = _status
    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines

    private var job: Job? = null
    @Volatile private var session: LiveSession? = null
    @Volatile private var turnDone = false

    /** Brain voice mode: "assistant", "interview_live" (quiet cues) or "interview_practice". */
    @Volatile var mode = "assistant"
        private set

    /** Switch mode; a running conversation restarts in the new mode straight away. */
    fun setMode(newMode: String) {
        if (newMode == mode) return
        mode = newMode
        awake = false
        GlassesCamera.stopPeriodicCapture()
        session?.close()
    }

    /** Switches the app's mode by name ("Game", "Interview mode"…); returns the mode, or null if there's none. */
    var onModeRequest: ((String) -> String?)? = null

    /** Whether the user is at home (null: not known yet); sent with each voice session for the TV/laptop "show". */
    var atHome: () -> Boolean? = { null }

    /** Only listen after the user says "Ronie" (otherwise Ronie answers everything it hears). */
    @Volatile var wakeWordOn = true
    @Volatile private var awake = false
    val isAwake: Boolean get() = awake
    @Volatile private var replying = false // from Ronie's first audio until its turn ends
    @Volatile private var lastActivityAt = 0L
    // A conversation lasts until the user says bye (end_conversation) or stays quiet even after "Aur kuch?".
    @Volatile private var nudgedAt = 0L
    @Volatile private var sleepAfterReply = false

    private fun shouldStartFastCamera(): Boolean {
        val app = context.applicationContext as? RonieApplication
        return app?.fastCamera ?: true
    }

    init {
        // A voice change on the laptop or in the app ends the session; the loop reopens it with the new voice.
        scope.launch { brain.pushes.collect { (type, _) -> if (type == "live.restart") session?.close() } }
    }

    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (running) return
        job = scope.launch { runLoop() }
    }

    fun stop() {
        Log.d("RonieVoice", "stop() called", Throwable("who stopped"))
        GlassesCamera.stopPeriodicCapture()
        job?.cancel()
        job = null
        session?.close()
        session = null
        _status.value = VoiceStatus.Off
        _detail.value = ""
    }

    private suspend fun runLoop() {
        _status.value = VoiceStatus.Starting
        var wake: WakeWord? = null
        if (wakeWordOn) {
            for (attempt in 1..3) {
                try {
                    wake = WakeWord.load(context) { _detail.value = it }
                    Diag.log("wake word ready")
                    break
                } catch (e: Exception) {
                    Diag.log("wake word didn't load (try $attempt): ${e.message}")
                    if (attempt < 3) delay(3_000)
                }
            }
            if (wake == null) _detail.value = "Wake word unavailable; listening to everything until the app restarts"
        }
        awake = wake == null
        if (awake && shouldStartFastCamera()) {
            GlassesCamera.startPeriodicCapture(3500L)
        }
        val recent = ArrayDeque<ByteArray>() // the last 4 s, so a whole "Ronie, …" sentence reaches the model
        val chime = Chime().also { this.chime = it }
        route.start()
        // Let a camera handshake briefly drop the glasses' call link (HFP/SCO); it comes back after.
        GlassesCamera.pauseCallAudio = route::pauseForCamera
        GlassesCamera.resumeCallAudio = route::resumeAfterCamera
        val speaker = Speaker(
            useMedia = { route.speakingMode },
            mediaDevice = route::musicLink,
            mediaReadyAt = { route.musicReadyAt },
        )
        val cues = InterviewCues(
            context, brain, scope,
            onLine = { _lines.value = (_lines.value + it).takeLast(20) },
            onStatus = { _detail.value = it },
        )
        val mic = Microphone { chunk ->
            // Interview cues: no chatbot; utterances go to the brain, answers are read out by the phone.
            if (mode == "interview_live") {
                cues.feed(chunk, loud(chunk))
                return@Microphone
            }
            // Ronie's whole turn has been played on the glasses' music link: bring their mic back.
            // (Waiting for the turn to end, not just a quiet moment, stops the link flipping mid-sentence.)
            if (route.speakingMode && turnDone && !speaker.busy()) {
                turnDone = false
                route.forListening()
            }
            val now = SystemClock.elapsedRealtime()
            // Interview modes listen all the time: the interviewer won't say "Ronie".
            val needsName = wake != null && mode == "assistant"
            if (needsName && !awake) {
                recent.addLast(chunk)
                if (recent.size > 40) recent.removeFirst()
                val woke = if (session != null) wake!!.heard(chunk) else null
                if (woke != null) {
                    Diag.log("awake ($woke)")
                    awake = true
                    lastActivityAt = now
                    nudgedAt = 0L
                    sleepAfterReply = false
                    chime.wake()
                    if (atHome() == true) scope.launch { glanceAround() }
                    if (shouldStartFastCamera()) {
                        GlassesCamera.startPeriodicCapture(3500L)
                    }
                    // Name plus a request in one breath: pass the request on. Name alone ("Hey Ronie"):
                    // send nothing, so there's nothing to reply to; the chime says Ronie is listening.
                    if (woke == Wake.WithRequest) recent.forEach { session?.sendAudio(it) }
                    recent.clear()
                    _status.value = VoiceStatus.Listening
                }
                return@Microphone
            }
            // Activity = Ronie talking, or anything the user says (from Gemini's transcript, so a soft voice counts and
            // room noise doesn't). Normal pauses don't end the conversation.
            if (speaker.busy() || replying) lastActivityAt = now
            if (needsName && !speaker.busy() && !replying) {
                val quietFor = now - lastActivityAt
                when {
                    // The user said bye and Ronie has finished its goodbye. (Not turnDone: switching the glasses back
                    // to their mic clears that flag first, so with glasses on this never fired.)
                    sleepAfterReply -> {
                        goToSleep("conversation ended")
                        return@Microphone
                    }
                    nudgedAt == 0L && quietFor > NUDGE_AFTER_MS -> {
                        nudgedAt = now
                        session?.sendText(QUIET_NOTE)
                    }
                    nudgedAt != 0L && now - maxOf(lastActivityAt, nudgedAt) > SLEEP_AFTER_NUDGE_MS -> {
                        goToSleep("no answer to 'anything else?'")
                        return@Microphone
                    }
                }
            }
            if (!route.speakingMode) session?.sendAudio(chunk)
            val s = _status.value
            if (s == VoiceStatus.Listening || s == VoiceStatus.Speaking) {
                _status.value = if (speaker.busy()) VoiceStatus.Speaking else VoiceStatus.Listening
            }
        }
        var modelIndex = 0
        try {
            while (scope.isActive && job?.isActive == true) {
                if (mode == "interview_live") {
                    session = null
                    _status.value = VoiceStatus.Listening
                    cues.begin()
                    while (mode == "interview_live" && job?.isActive == true) delay(300)
                    cues.end()
                    continue
                }
                _status.value = VoiceStatus.Starting
                _detail.value = "Connecting…"
                val ready: JsonObject = try {
                    brain.request("live.start", JsonObject(buildMap {
                        put("mode", JsonPrimitive(mode))
                        atHome()?.let { put("home", JsonPrimitive(it)) }
                    }), 15_000)
                } catch (e: BrainException) {
                    fail(e.message ?: "Brain server not reachable"); delay(3_000); continue
                } catch (e: TimeoutCancellationException) {
                    fail("The brain server didn't answer"); delay(3_000); continue
                }
                val config = ready["config"]!!.jsonObject
                val models = config["models"]!!.jsonArray.map { it.jsonPrimitive.content }
                if (modelIndex >= models.size) {
                    fail("No voice model could start. Check the Gemini key's free quota on the laptop.")
                    return
                }
                val ended = CompletableDeferred<Boolean>() // true = the session had started
                val s = LiveSession(
                    token = ready["token"]!!.jsonPrimitive.content,
                    url = ready["url"]!!.jsonPrimitive.content,
                    model = models[modelIndex],
                    config = config,
                    brain = brain,
                    speaker = speaker,
                    scope = scope,
                    listener = object : LiveSession.Listener {
                        override fun onReady() {
                            _status.value = if (awake || wake == null || mode != "assistant") VoiceStatus.Listening else VoiceStatus.Waiting
                            _detail.value = "Listening"
                        }
                        override fun onSetupFailed(reason: String) {
                            _detail.value = "${models[modelIndex]} didn't start: $reason"
                            ended.complete(false)
                        }
                        override fun onClosed() { ended.complete(true) }
                        override fun onTurnComplete() {
                            turnDone = true
                            replying = false
                        }
                        override fun onTranscript(who: String, text: String) {
                            _lines.value = (_lines.value + Line(who, text)).takeLast(20)
                            lastActivityAt = SystemClock.elapsedRealtime()
                            if (who == "you") nudgedAt = 0L // they answered: the conversation goes on
                            noticeGoodbye(who, text)
                        }
                    },
                    canInterrupt = { route.canInterrupt },
                    beforePlay = {
                        turnDone = false
                        replying = true
                        route.forSpeaking()
                    },
                    localTool = ::runLocalTool,
                )
                session = s
                s.open(http)
                val started = ended.await()
                session = null
                if (!started) modelIndex++ // try the next Live model
            }
        } catch (e: Throwable) {
            Log.w("RonieVoice", "voice loop ended", e)
            throw e
        } finally {
            GlassesCamera.stopPeriodicCapture()
            session?.close()
            session = null
            mic.release()
            cues.end()
            cues.release()
            speaker.release()
            chime.release()
            this.chime = null
            GlassesCamera.pauseCallAudio = null
            GlassesCamera.resumeCallAudio = null
            route.stop()
        }
    }

    // The last words of each side, to notice a goodbye even when the voice model forgets to call end_conversation.
    private var heardTail = ""
    private var saidTail = ""

    private fun noticeGoodbye(who: String, text: String) {
        if (!awake) return
        if (who == "you") heardTail = (heardTail + " " + text).takeLast(120) else saidTail = (saidTail + " " + text).takeLast(120)
        if (USER_GOODBYE.containsMatchIn(heardTail.lowercase()) || RONIE_GOODBYE.containsMatchIn(saidTail.lowercase())) {
            Diag.log("goodbye noticed (${if (who == "you") "user" else "Ronie"}): ${text.take(40)}")
            sleepAfterReply = true // after Ronie's goodbye finishes, back to waiting for "Ronie"
            heardTail = ""
            saidTail = ""
        }
    }

    private var chime: Chime? = null

    private fun goToSleep(why: String) {
        GlassesCamera.stopPeriodicCapture()
        chime?.sleep() // the falling chime: Ronie is waiting for its name again
        heardTail = ""
        saidTail = ""
        awake = false
        nudgedAt = 0L
        sleepAfterReply = false
        Diag.log("back to waiting for the wake word ($why)")
        _status.value = VoiceStatus.Waiting
    }

    private var lastGlanceAt = 0L

    /**
     * At home, a photo as the user calls Ronie, so the brain knows which screen is in front of them (laptop or TV)
     * and shows things there. At most once a minute; the photo isn't kept.
     */
    private suspend fun glanceAround() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGlanceAt < 60_000) return
        lastGlanceAt = now
        brain.send("surroundings.pending", JsonObject(emptyMap())) // so "show" waits for this photo
        try {
            val (jpeg, _) = GlassesCamera.getLatestOrTakePhoto(maxAgeMs = 3_000L, maxSide = 640)
            brain.send("surroundings", JsonObject(mapOf(
                "image_b64" to JsonPrimitive(android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)))))
            Diag.log("surroundings: photo sent (${jpeg.size / 1024} KB)")
        } catch (e: Exception) {
            brain.send("surroundings", JsonObject(emptyMap())) // no photo: don't keep "show" waiting
            Diag.log("surroundings: no photo (${e.message})")
        }
    }

    /** "look": take a photo with the glasses; "end_conversation": back to waiting for the name after the goodbye. */
    private suspend fun runLocalTool(name: String, args: JsonObject, session: LiveSession): JsonObject? {
        if (name == "battery") {
            return JsonObject(Battery.describe(context).mapValues { JsonPrimitive(it.value) })
        }
        if (name == "set_mode") {
            val wanted = args["mode"]?.jsonPrimitive?.content.orEmpty()
            val known = com.ronie.app.MODES.firstOrNull { it.equals(wanted, ignoreCase = true) }
                ?: return JsonObject(mapOf("error" to JsonPrimitive("There's no mode called '$wanted'.")))
            Diag.log("mode change by voice: $known")
            // After Ronie has said so: interview modes restart the voice session.
            scope.launch {
                delay(3_000)
                withContext(Dispatchers.Main) { onModeRequest?.invoke(known) }
            }
            return JsonObject(mapOf("result" to JsonPrimitive("Switching to $known. Say so in a few words.")))
        }
        if (name == "end_conversation") {
            sleepAfterReply = true
            return JsonObject(mapOf("result" to JsonPrimitive("Done. Say your short goodbye now if you haven't.")))
        }
        if (name != "look") return null
        brain.event("vision", "Getting photo from glasses")
        return try {
            val (jpeg, ageMs) = GlassesCamera.getLatestOrTakePhoto(maxAgeMs = 3500L)
            val note = if (ageMs > 0) "Pulled fresh cached photo (${jpeg.size / 1024} KB, ${ageMs}ms ago)"
                       else "Captured photo (${jpeg.size / 1024} KB)"
            brain.event("vision", note)
            Diag.log(note)
            session.afterNextToolReply {
                session.sendImageTurn(
                    jpeg,
                    "This is a fresh photo from my glasses, taken just now. It replaces any earlier photo. Answer my " +
                        "question only from what is actually in this photo. For any later question about what I'm " +
                        "looking at or holding, call look again for a new photo.",
                )
            }
            JsonObject(mapOf("result" to JsonPrimitive("The photo is coming in the next message. Say nothing until it arrives.")))
        } catch (e: Exception) {
            brain.event("error", "Glasses photo failed: ${e.message}")
            JsonObject(mapOf("error" to JsonPrimitive("Couldn't take a photo: ${e.message}")))
        }
    }

    private fun loud(pcm: ByteArray): Boolean {
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            if (s > SPEECH_LEVEL || s < -SPEECH_LEVEL) return true
            i += 2
        }
        return false
    }

    private companion object {
        val USER_GOODBYE = Regex(
            "\\b(bye|bye bye|bye-bye|goodbye|that'?s all|that is all|no thanks|bas itna( hi)?|kuch nahi chahiye|" +
                "aur kuch nahi|chalo bye|alvida|good night)\\b|बाय|अलविदा|कुछ नहीं चाहिए|बस इतना"
        )
        val RONIE_GOODBYE = Regex("\\b(bye|goodbye|phir milte|take care|alvida|good night)\\b|बाय|फिर मिलते")
        const val NUDGE_AFTER_MS = 20_000L // this much quiet in a conversation: Ronie asks "Aur kuch?"
        const val SLEEP_AFTER_NUDGE_MS = 12_000L // still nothing after that: back to waiting for "Ronie"
        const val QUIET_NOTE = "(Note from the app, not the user, don't read it out: the user has been quiet for a " +
            "while. Ask in a few words, in their language, whether they need anything else. If they say no, just say a " +
            "short bye.)"
        const val SPEECH_LEVEL = 1500
    }

    private fun fail(message: String) {
        _status.value = VoiceStatus.Error
        _detail.value = message
    }
}
