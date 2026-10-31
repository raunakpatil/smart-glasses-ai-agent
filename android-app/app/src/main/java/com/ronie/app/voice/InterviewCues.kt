package com.ronie.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import com.ronie.app.brain.BrainClient
import com.ronie.app.brain.BrainException
import com.ronie.app.brain.BrainState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Interview cues: no chatbot listens. Speech is cut into utterances; the brain transcribes each one, decides whether
 * it's the interviewer asking the candidate something, and if so writes the candidate's answer from their profile.
 * The answer is a few cue points (shown on the phone), whispered into the user's ear one at a time: after each point
 * Ronie waits while the user talks about it in their own words, and gives the next one when they pause. Ronie goes
 * quiet whenever the interviewer talks. Everything else is ignored.
 *
 * The glasses' mic is tuned to the wearer and drops other voices, so for a call the brain records the interviewer
 * straight from the laptop's audio and the glasses' mic is only the user. If the laptop can't do that (an interview
 * in person), both voices come through the glasses and are told apart by loudness and words.
 */
class InterviewCues(
    context: Context,
    private val brain: BrainClient,
    private val scope: CoroutineScope,
    private val onLine: (Line) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val segment = ByteArrayOutputStream()
    private val before = ArrayDeque<ByteArray>() // a little audio from just before speech starts
    private var inSpeech = false
    private var speechMs = 0
    private var quietMs = 0
    // Loudness of the voiced chunks of the current utterance (sum of squares and sample count).
    private var energy = 0.0
    private var samples = 0L
    /** The first thing said after starting is the user's warm-up ("I'm ready"): it teaches Ronie their voice level. */
    @Volatile private var calibrated = false
    @Volatile private var speaking = false
    @Volatile private var speakingEndedAt = 0L
    @Volatile private var ttsReady = false
    /** The brain is hearing the interviewer from the laptop's audio. */
    @Volatile private var laptop = false
    private var watch: Job? = null

    // Phrase by phrase: what's left to say, and whether we're waiting for the user to repeat the last phrase.
    private val lock = Any()
    private val phrases = ArrayDeque<String>()
    private var current = ""
    private var currentId = ""
    private var currentAnswer = ""
    private var phraseCount = 0
    private var plays = 0 // how many times the current phrase has been said
    @Volatile private var awaitingRepeat = false
    private var voicedMs = 0 // how much the user has spoken since the phrase
    private var repeatQuietMs = 0
    private var waitedMs = 0
    // The brain checks what the user said against the phrase; the next phrase waits briefly for that check.
    private var checkPending = false
    private var checkWaitMs = 0
    @Volatile private var lastPhraseAt = 0L
    // The interviewer is talking (heard on the laptop): Ronie goes quiet, then carries on or switches answers.
    @Volatile private var interviewerTalking = false
    @Volatile private var interviewerStoppedAt = 0L
    private var paused = false
    private var resumeJob: Job? = null
    // Background-noise level of the glasses' mic, so a soft voice still counts as speaking.
    private var noiseDb = -60.0

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.language = Locale("en", "IN")
            tts.setSpeechRate(0.95f) // calm, so a phrase is easy to catch in one hearing
            // On the glasses' call link, like the mic: no switching links between phrases, and the mic stays on
            // to hear the user repeat each one.
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { phraseSaid(utteranceId) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { phraseSaid(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { phraseSaid(utteranceId) }
        })
    }

    /** One 100 ms mic chunk (16 kHz PCM). [loudPeak]: the caller's fixed-level check. */
    fun feed(chunk: ByteArray, loudPeak: Boolean) {
        // Don't record our own answer being read out (or the moment right after).
        // Our own phrase (or its echo), or the interviewer talking: none of it is the user.
        val now = SystemClock.elapsedRealtime()
        if (speaking || interviewerTalking || now - speakingEndedAt < ECHO_MS || now - interviewerStoppedAt < INTERVIEWER_TAIL_MS) {
            reset()
            return
        }
        val loud = loudPeak || isVoice(chunk)
        if (awaitingRepeat) trackRepeat(loud)
        if (!inSpeech) {
            before.addLast(chunk)
            if (before.size > 3) before.removeFirst()
            if (!loud) return
            inSpeech = true
            segment.reset()
            before.forEach { segment.write(it) } // already includes this chunk
            before.clear()
            speechMs = 100
            quietMs = 0
            addEnergy(chunk)
            return
        }
        segment.write(chunk)
        speechMs += 100
        quietMs = if (loud) 0 else quietMs + 100
        if (loud) addEnergy(chunk)
        if (quietMs >= END_OF_UTTERANCE_MS || speechMs >= MAX_UTTERANCE_MS) {
            val pcm = segment.toByteArray()
            val voiced = speechMs - quietMs
            val level = levelDb()
            reset()
            if (voiced >= MIN_SPEECH_MS) send(pcm, level)
        }
    }

    /** Speech compared with the room's background noise (learned from the quiet moments). */
    private fun isVoice(pcm: ByteArray): Boolean {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
            sum += s * s
            n++
            i += 2
        }
        val db = if (n == 0) -90.0 else 20 * kotlin.math.log10((kotlin.math.sqrt(sum / n) / 32768.0).coerceAtLeast(1e-6))
        val voice = db > noiseDb + VOICE_ABOVE_NOISE_DB && db > -58
        if (!voice) noiseDb = if (db < noiseDb) db else noiseDb * 0.95 + db * 0.05
        return voice
    }

    private fun reset() {
        inSpeech = false
        segment.reset()
        speechMs = 0
        quietMs = 0
        energy = 0.0
        samples = 0
    }

    private fun addEnergy(pcm: ByteArray) {
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
            energy += s * s
            samples++
            i += 2
        }
    }

    /** Average loudness of the speech in dBFS (0 = full scale; quieter is more negative). */
    private fun levelDb(): Double {
        if (samples == 0L) return -90.0
        val rms = kotlin.math.sqrt(energy / samples) / 32768.0
        return 20 * kotlin.math.log10(rms.coerceAtLeast(1e-6))
    }

    private fun send(pcm: ByteArray, levelDb: Double) {
        // Said while being fed an answer: the brain checks it against the phrase.
        val (expected, answer, forId) = synchronized(lock) {
            if (SystemClock.elapsedRealtime() - lastPhraseAt < 30_000 && current.isNotBlank()) {
                if (awaitingRepeat) checkPending = true
                Triple(current, currentAnswer, currentId)
            } else Triple("", "", "")
        }
        val feeding = expected.isNotBlank()
        scope.launch {
            if (!feeding) onStatus("Thinking…")
            try {
                val r = brain.request(
                    "interview.segment",
                    JsonObject(mapOf(
                        "audio_b64" to JsonPrimitive(Base64.encodeToString(pcm, Base64.NO_WRAP)),
                        "level_db" to JsonPrimitive(levelDb),
                        "calibrate" to JsonPrimitive(!laptop && !calibrated && !feeding),
                        "speaker" to JsonPrimitive(if (laptop || feeding) "candidate" else ""),
                        "expected" to JsonPrimitive(expected),
                        "answer" to JsonPrimitive(answer),
                    )),
                    60_000,
                )
                if (feeding) checked(forId, r["check"] as? JsonObject)
                if (r["calibrated"] != null) {
                    calibrated = true
                    onLine(Line("room", "Got your voice (${levelDb.toInt()} dB). Ready for the interviewer."))
                    return@launch
                }
                show(r, if (laptop) "glasses mic" else "${levelDb.toInt()} dB")
            } catch (e: BrainException) {
                val msg = if (e.message?.startsWith("Not handled") == true)
                    "The brain server is an old version. Restart it (start-ronie.cmd) on the laptop."
                else "couldn't answer: ${e.message}"
                onLine(Line("room", "($msg)"))
            } catch (e: TimeoutCancellationException) {
                onLine(Line("room", "(the brain took too long)"))
            } finally {
                if (feeding) checked(forId, null) else if (!speaking && !awaitingRepeat) onStatus(idleStatus())
            }
        }
    }

    /** The brain's verdict on what the user said for a phrase: a correction goes in before the next phrase. */
    private fun checked(forId: String, check: JsonObject?) {
        val correction = check?.get("correction")?.jsonPrimitive?.content.orEmpty()
        synchronized(lock) {
            if (forId == currentId) checkPending = false
            if (correction.isNotBlank()) phrases.addFirst(correction)
        }
        if (correction.isNotBlank()) {
            onLine(Line("room", "(fix) $correction"))
            // Already done with the answer and waiting for nothing: say the fix now.
            val idle = synchronized(lock) { !awaitingRepeat && !speaking }
            if (idle) nextPhrase(flush = false)
        }
    }

    private fun idleStatus() = when {
        laptop -> "Listening to the interviewer on the laptop"
        calibrated -> "Listening for the interviewer…"
        else -> "Say \"I'm ready\" so Ronie learns your voice"
    }

    /** An answer from the brain: read it out if the interviewer asked something, else show what was heard. */
    private fun show(r: JsonObject, where: String) {
        val heard = r["heard"]?.jsonPrimitive?.content.orEmpty()
        val isQuestion = r["interviewer_question"]?.jsonPrimitive?.boolean == true
        val answer = r["answer"]?.jsonPrimitive?.content.orEmpty()
        if (isQuestion && answer.isNotBlank()) {
            val question = r["question"]?.jsonPrimitive?.content ?: heard
            onLine(Line("cue", "Interviewer ($where): $question\n\n$answer"))
            val parts = (r["phrases"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            speak(answer, parts)
        } else if (heard.isNotBlank()) {
            val who = r["speaker"]?.jsonPrimitive?.content ?: "unclear"
            val label = when (who) { "candidate" -> "you"; "interviewer" -> "interviewer"; else -> "?" }
            onLine(Line("room", "($label, $where) $heard"))
        } else r["error"]?.jsonPrimitive?.content?.let { onLine(Line("room", "(couldn't answer: $it)")) }
    }

    /** Interview cues started: ask the brain to hear the interviewer from the laptop (again after a reconnect). */
    fun begin() {
        watch?.cancel()
        watch = scope.launch {
            launch {
                brain.pushes.collect { (type, payload) ->
                    if (!laptop) return@collect
                    when (type) {
                        "interview.interviewer" -> interviewer(
                            payload["talking"]?.jsonPrimitive?.boolean == true,
                            payload["transcribing"]?.jsonPrimitive?.boolean == true,
                        )
                        "interview.answer" -> {
                            resumeJob?.cancel()
                            val isQuestion = payload["interviewer_question"]?.jsonPrimitive?.boolean == true
                            show(payload, "laptop")
                            if (!isQuestion) resume() // "mm-hmm", "okay": carry on with the answer
                        }
                    }
                }
            }
            brain.state.collect { state ->
                if (state != BrainState.Connected) return@collect
                laptop = try {
                    val r = brain.request("interview.listen", JsonObject(mapOf("on" to JsonPrimitive(true))), 10_000)
                    val device = r["device"]?.jsonPrimitive?.content.orEmpty()
                    onLine(Line("room", "Hearing the interviewer from the laptop ($device). The glasses' mic is only you."))
                    true
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    onLine(Line("room", "(Laptop audio not available: ${e.message}. Using the glasses' mic for both voices.)"))
                    false
                }
                if (!speaking) onStatus(idleStatus())
            }
        }
    }

    fun end() {
        watch?.cancel()
        watch = null
        if (laptop) runCatching { brain.send("interview.listen", JsonObject(mapOf("on" to JsonPrimitive(false)))) }
        laptop = false
        stopSpeaking()
    }

    /** The interviewer started or stopped talking. While they talk Ronie is silent; afterwards it either answers their
     *  new question or, if they didn't ask anything, picks up the phrase it was on. */
    private fun interviewer(talking: Boolean, transcribing: Boolean) {
        resumeJob?.cancel()
        interviewerTalking = talking
        if (!talking) interviewerStoppedAt = SystemClock.elapsedRealtime()
        if (talking) {
            val stop = synchronized(lock) {
                voicedMs = 0 // the glasses may have caught the interviewer's first words: that wasn't the user
                if (current.isBlank() || !(speaking || awaitingRepeat)) return@synchronized false
                paused = true
                awaitingRepeat = false
                currentId = "" // the phrase being cut off doesn't count as said
                true
            }
            if (stop) {
                speaking = false
                tts.stop()
                onStatus("Interviewer is talking…")
            }
        } else {
            // What they said is being transcribed: its answer decides (new answer, or carry on). Don't wait forever.
            resumeJob = scope.launch {
                delay(if (transcribing) RESUME_AFTER_MS.toLong() else 300L)
                resume()
            }
        }
    }

    private fun resume() {
        val phrase = synchronized(lock) {
            if (!paused || interviewerTalking) return
            paused = false
            current
        }
        if (phrase.isNotBlank()) say(phrase, again = false)
    }

    /** A new answer: replaces whatever is still being fed (the newest question wins). */
    private fun speak(answer: String, parts: List<String>) {
        if (!ttsReady) return
        resumeJob?.cancel()
        synchronized(lock) {
            paused = false
            phrases.clear()
            phrases.addAll(parts.ifEmpty { answer.split(Regex("(?<=[.!?])\\s+")) }.filter { it.isNotBlank() })
            currentAnswer = answer
            awaitingRepeat = false
        }
        nextPhrase(flush = false) // not on the mic thread: leave its open utterance alone
    }

    /**
     * After a phrase: wait for the user to say it, in their own words or language, and to finish (a real pause),
     * then the next phrase. If they say nothing, the phrase is said again after a while, at most three times in all;
     * after that Ronie just waits. It never moves on by itself while they're quiet.
     */
    private fun trackRepeat(loud: Boolean) {
        var next = false
        var replay = false
        synchronized(lock) {
            if (!awaitingRepeat) return
            waitedMs += 100
            if (loud) {
                voicedMs += 100
                repeatQuietMs = 0
            } else repeatQuietMs += 100
            val spoke = voicedMs >= MIN_REPEAT_MS
            when {
                spoke && repeatQuietMs >= FINISHED_PAUSE_MS -> {
                    // Finished. Give the brain's check a moment, so a correction comes before the next phrase.
                    if (checkPending && checkWaitMs < CHECK_WAIT_MS) checkWaitMs += 100 else next = true
                }
                spoke -> {}
                plays == 1 && waitedMs >= SECOND_PLAY_MS -> replay = true
                plays == 2 && waitedMs >= THIRD_PLAY_MS -> replay = true
            }
            if (next || replay) awaitingRepeat = false
        }
        if (replay) say(current, again = true) else if (next) nextPhrase()
    }

    private fun nextPhrase(flush: Boolean = true) {
        // The user's repeat may still be an open utterance: send it now, before our next phrase cuts it off.
        if (flush && inSpeech && speechMs - quietMs >= MIN_SPEECH_MS) {
            val pcm = segment.toByteArray()
            val level = levelDb()
            reset()
            send(pcm, level)
        }
        val phrase = synchronized(lock) { phrases.removeFirstOrNull() }
        if (phrase == null) {
            onStatus(idleStatus())
            return
        }
        say(phrase, again = false)
    }

    private fun say(phrase: String, again: Boolean) {
        val wait = synchronized(lock) {
            if (interviewerTalking) { // never talk over the interviewer: say it when they stop
                current = phrase
                paused = true
                true
            } else false
        }
        if (wait) return
        synchronized(lock) {
            current = phrase
            currentId = "phrase-${++phraseCount}"
            plays = if (again) plays + 1 else 1
            checkPending = false
            checkWaitMs = 0
            lastPhraseAt = SystemClock.elapsedRealtime()
        }
        speaking = true
        onStatus("Point: $phrase")
        tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, currentId)
    }

    /** A phrase finished (or was cut off): now wait for the user to say it. */
    private fun phraseSaid(utteranceId: String?) {
        synchronized(lock) {
            if (utteranceId != currentId) return // an older phrase, replaced by a newer answer
            speaking = false
            speakingEndedAt = SystemClock.elapsedRealtime()
            waitedMs = 0
            voicedMs = 0
            repeatQuietMs = 0
            awaitingRepeat = true
        }
    }

    fun stopSpeaking() {
        synchronized(lock) {
            phrases.clear()
            awaitingRepeat = false
            currentId = ""
            current = ""
            paused = false
        }
        resumeJob?.cancel()
        speaking = false
        tts.stop()
    }

    fun release() {
        tts.stop()
        tts.shutdown()
    }

    private companion object {
        const val ECHO_MS = 350 // ignore the mic this long after a phrase (its echo)
        const val INTERVIEWER_TAIL_MS = 600 // and this long after the interviewer stops (the laptop speaker's tail)
        const val MIN_REPEAT_MS = 800 // speech shorter than this ("um", "so…") doesn't count as talking about the point
        const val FINISHED_PAUSE_MS = 1_000 // the user talked and then paused this long: done with this point
        const val CHECK_WAIT_MS = 800 // then wait at most this long for the brain's check (a late correction comes next)
        const val RESUME_AFTER_MS = 8_000 // the interviewer stopped: carry on after this if no answer comes
        const val SECOND_PLAY_MS = 5_000 // nothing said this long after the phrase: say it again
        const val THIRD_PLAY_MS = 10_000 // and once more after a longer gap; then just wait
        const val VOICE_ABOVE_NOISE_DB = 9.0
        const val END_OF_UTTERANCE_MS = 800 // this much quiet ends an utterance
        const val MAX_UTTERANCE_MS = 30_000
        const val MIN_SPEECH_MS = 500 // ignore coughs and clicks
    }
}
