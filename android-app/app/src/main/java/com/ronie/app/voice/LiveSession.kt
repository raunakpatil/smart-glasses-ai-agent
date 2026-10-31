package com.ronie.app.voice

import android.os.SystemClock
import android.util.Base64
import com.ronie.app.brain.BrainClient
import com.ronie.app.brain.BrainException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * One Gemini Live voice session, opened with a one-use token from the brain (the API key stays on
 * the laptop). Same behaviour as pc-companion/ronie_pc/live.py: mic audio in, Ronie's audio out,
 * tool calls run by the brain, and what was said is reported so it lands in the shared memory.
 */
class LiveSession(
    private val token: String,
    private val url: String,
    private val model: String,
    private val config: JsonObject,
    private val brain: BrainClient,
    private val speaker: Speaker,
    private val scope: CoroutineScope,
    private val listener: Listener,
    /** True when the user may talk over Ronie (on the glasses, which cancel their own echo). */
    private val canInterrupt: () -> Boolean = { false },
    /** Called before Ronie's audio plays (switches the glasses to their clearer music link). */
    private val beforePlay: () -> Unit = {},
    /** Tools the phone runs itself (the glasses camera). Returns null for tools the brain should run. */
    private val localTool: suspend (name: String, args: JsonObject, session: LiveSession) -> JsonObject? = { _, _, _ -> null },
) {
    interface Listener {
        fun onReady()
        fun onSetupFailed(reason: String)
        fun onClosed()
        fun onTranscript(who: String, text: String)
        /** Ronie's turn is over (finished or interrupted); everything it will say is already queued. */
        fun onTurnComplete() {}
    }

    private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var finished = false
    private val heard = StringBuilder()
    private val said = StringBuilder()
    @Volatile private var lastVoiceAt = 0L
    @Volatile private var firstAudioMs: Long? = null
    /** The user said "stop": the rest of Ronie's current answer isn't played. */
    @Volatile private var muted = false
    private val overTalk = StringBuilder() // what the user said while Ronie was talking

    fun open(http: OkHttpClient) {
        val request = Request.Builder().url("$url?access_token=$token").build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage().toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(webSocket, bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                end(reason.ifBlank { "closed ($code)" })
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end(reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                end(t.message ?: t.javaClass.simpleName)
        })
    }

    private fun end(reason: String) {
        if (finished) return
        finished = true
        if (ready) listener.onClosed() else listener.onSetupFailed(reason)
    }

    fun close() {
        ws?.close(1000, null)
    }

    /** Sends one mic chunk (16 kHz PCM). On the phone speaker it's muted while Ronie talks so it doesn't hear itself. */
    fun sendAudio(pcm: ByteArray) {
        if (!ready || (speaker.busy() && !canInterrupt())) return
        if (peak(pcm) > SPEECH_PEAK) lastVoiceAt = SystemClock.elapsedRealtime()
        val msg = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("audio") {
                    put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
                    put("mimeType", "audio/pcm;rate=$MIC_RATE")
                }
            }
        }
        ws?.send(msg.toString())
    }

    /** A short text note from the app into the conversation, e.g. "the user has gone quiet". */
    fun sendText(text: String) {
        val msg = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { addJsonObject { put("text", text) } }
                    }
                }
                put("turnComplete", true)
            }
        }
        ws?.send(msg.toString())
    }

    /**
     * Puts a photo into the conversation as a message with an instruction. Tested: every Live model reads an image
     * sent this way, while a single "video frame" was ignored or even invented (a red sports car for a purple sphere).
     */
    fun sendImageTurn(jpeg: ByteArray, text: String) {
        val msg = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            addJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", "image/jpeg")
                                    put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                                }
                            }
                            addJsonObject { put("text", text) }
                        }
                    }
                }
                put("turnComplete", true)
            }
        }
        ws?.send(msg.toString())
    }

    /** Runs once, right after the next tool reply is sent (the photo must follow the "look" reply). */
    @Volatile private var afterToolReply: (() -> Unit)? = null

    fun afterNextToolReply(action: () -> Unit) {
        afterToolReply = action
    }

    private fun setupMessage(): JsonObject = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/$model")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                val voice = config["voice"]?.jsonPrimitive?.content.orEmpty()
                if (voice.isNotEmpty()) {
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") {
                            putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) }
                        }
                    }
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", config["system"]?.jsonPrimitive?.content.orEmpty()) } }
            }
            putJsonArray("tools") {
                addJsonObject { put("functionDeclarations", config["functions"] ?: JsonArray(emptyList())) }
            }
            putJsonObject("inputAudioTranscription") {
                // Names and words to expect (Ronie, Stremio, JioHotstar…); gemini-3.8-live uses them.
                config["vocabulary"]?.let { put("customVocabulary", it) }
            }
            putJsonObject("outputAudioTranscription") {}
            // Long conversations drop the oldest audio instead of ending; the brain's memory keeps everything.
            putJsonObject("contextWindowCompression") { putJsonObject("slidingWindow") {} }
            putJsonObject("realtimeInputConfig") {
                putJsonObject("automaticActivityDetection") {
                    // Low start sensitivity: room noise and half-words no longer start a turn (the logs showed Ronie
                    // answering stray sounds transcribed as Japanese or Portuguese).
                    put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                    put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                    put("silenceDurationMs", 500)
                    put("prefixPaddingMs", 300) // with low start sensitivity, keep the first word from being cut
                }
            }
        }
    }

    private fun handle(webSocket: WebSocket, text: String) {
        val msg = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        if ("setupComplete" in msg) {
            ready = true
            brain.event("connect", "Voice session started on the phone ($model)")
            listener.onReady()
        }
        msg["serverContent"]?.jsonObject?.let { content ->
            content["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.content?.let {
                heard.append(it)
                // The user said stop while Ronie talks: quiet now, not when the server notices. The rest of this
                // answer is dropped; the user's words still go to the model, which listens for what comes next.
                // Only words said over Ronie count, so a "bas" in the question doesn't cut the answer off.
                if (speaker.busy()) overTalk.append(it) else overTalk.clear()
                if (!muted && speaker.busy() && STOP_WORDS.containsMatchIn(overTalk)) {
                    muted = true
                    speaker.clear()
                    Diag.log("stopped talking: the user said \"${overTalk.trim()}\"")
                    overTalk.clear()
                }
            }
            content["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.content?.let { said.append(it) }
            content["modelTurn"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
                part.jsonObject["inlineData"]?.jsonObject?.get("data")?.jsonPrimitive?.content?.let { data ->
                    if (firstAudioMs == null && lastVoiceAt > 0) firstAudioMs = SystemClock.elapsedRealtime() - lastVoiceAt
                    if (!muted) {
                        beforePlay()
                        speaker.play(Base64.decode(data, Base64.DEFAULT))
                    }
                }
            }
            if (content["interrupted"]?.jsonPrimitive?.content == "true") {
                speaker.clear()
                muted = false
                Diag.log("interrupted by the user")
                listener.onTurnComplete()
            }
            if (content["turnComplete"]?.jsonPrimitive?.content == "true") {
                finishTurn()
                muted = false
                listener.onTurnComplete()
            }
        }
        msg["toolCall"]?.jsonObject?.get("functionCalls")?.jsonArray?.forEach { call ->
            scope.launch { runTool(webSocket, call.jsonObject) }
        }
    }

    private fun finishTurn() {
        val you = heard.toString().trim()
        val ronie = said.toString().trim()
        if (you.isNotEmpty()) {
            listener.onTranscript("you", you)
            brain.event("heard", you, mapOf("source" to "voice"))
        }
        if (ronie.isNotEmpty()) {
            listener.onTranscript("ronie", ronie)
            val extra = mutableMapOf("source" to "voice", "provider" to "gemini live", "model" to model)
            firstAudioMs?.let { extra["ms"] = it.toString() }
            brain.event("answer", ronie, extra)
        }
        heard.clear(); said.clear(); firstAudioMs = null; lastVoiceAt = 0
    }

    private suspend fun runTool(webSocket: WebSocket, call: JsonObject) {
        val name = call["name"]?.jsonPrimitive?.content.orEmpty()
        val args = call["args"]?.jsonObject ?: JsonObject(emptyMap())
        // Every tool runs in the brain (shared memory, web, models) except what needs the phone itself.
        val result: JsonObject = localTool(name, args, this) ?: try {
            brain.request("tool.call", buildJsonObject { put("name", name); put("args", args) }, 90_000)["result"]?.jsonObject
                ?: JsonObject(emptyMap())
        } catch (e: BrainException) {
            JsonObject(mapOf("error" to JsonPrimitive("Ronie's brain couldn't answer: ${e.message}")))
        } catch (e: TimeoutCancellationException) {
            JsonObject(mapOf("error" to JsonPrimitive("Ronie's brain took too long")))
        }
        if (name == "interview_cue" && result["keywords"] != null) {
            // Show the full answer card on the phone; Ronie whispers only the keywords.
            val keywords = result["keywords"]?.jsonPrimitive?.content.orEmpty()
            val card = result["card"]?.jsonPrimitive?.content.orEmpty()
            listener.onTranscript("cue", "$keywords\n$card")
        }
        val reply = buildJsonObject {
            putJsonObject("toolResponse") {
                putJsonArray("functionResponses") {
                    addJsonObject {
                        call["id"]?.let { put("id", it) }
                        put("name", name)
                        put("response", result)
                    }
                }
            }
        }
        webSocket.send(reply.toString())
        afterToolReply?.let { afterToolReply = null; it() }
    }

    private fun peak(pcm: ByteArray): Int {
        var max = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val a = kotlin.math.abs(s.toShort().toInt())
            if (a > max) max = a
            i += 2
        }
        return max
    }

    private companion object {
        const val SPEECH_PEAK = 1500
        val STOP_WORDS = Regex(
            "\\b(stop|ruko|ruk ja|ruk jao|bas|bas karo|chup|chup karo|enough|shut up|wait|ek minute)\\b|रुको|रुक जा|बस|चुप",
            RegexOption.IGNORE_CASE,
        )
    }
}
