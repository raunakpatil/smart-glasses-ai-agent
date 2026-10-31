package com.ronie.app.brain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Pairing details from the brain server's QR code (see shared/PROTOCOL.md). */
data class BrainPairing(val host: String, val port: Int, val token: String, val url: String? = null) {
    /** The phone's WebSocket address: the brain's public url (a cloud server, wss://) if it has one, else the LAN. */
    val socketUrl: String get() = url?.let { "${it.trimEnd('/')}/ws/phone" } ?: "ws://$host:$port/ws/phone"

    companion object {
        fun fromQr(text: String): BrainPairing? = runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            require(o["app"]?.jsonPrimitive?.content == "ronie")
            BrainPairing(
                host = o.getValue("host").jsonPrimitive.content,
                port = o.getValue("port").jsonPrimitive.content.toInt(),
                token = o.getValue("token").jsonPrimitive.content,
                url = o["url"]?.jsonPrimitive?.content?.ifBlank { null },
            )
        }.getOrNull()
    }
}

enum class BrainState { Disconnected, Connecting, Connected, Rejected }

class BrainException(message: String) : Exception(message)

/** One of Gemini Live's built-in voices, as listed by the brain ("voices" message). */
data class Voice(val name: String, val style: String, val type: String, val current: Boolean)

/** WebSocket link to the brain server: hello, then protocol v1 messages. */
class BrainClient(private val device: String) {
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null

    private val _state = MutableStateFlow(BrainState.Disconnected)
    val state: StateFlow<BrainState> = _state

    private val _voices = MutableStateFlow<List<Voice>>(emptyList())
    val voices: StateFlow<List<Voice>> = _voices

    var onMessage: (type: String, id: String?, payload: JsonObject) -> Unit = { _, _, _ -> }

    // Replies to request(), matched by message id.
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Pair<String, JsonObject>>>()

    /** Messages the brain sends on its own, e.g. "live.restart" when the voice changes. */
    private val _pushes = MutableSharedFlow<Pair<String, JsonObject>>(extraBufferCapacity = 16)
    val pushes: SharedFlow<Pair<String, JsonObject>> = _pushes

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var pairing: BrainPairing? = null
    private var retrySeconds = 1L
    private var stopped = false

    /** Connects and keeps reconnecting (1 s, 2 s, 4 s … up to 30 s) until close() or a rejected pairing. */
    fun connect(pairing: BrainPairing) {
        this.pairing = pairing
        stopped = false
        retrySeconds = 1
        open()
    }

    private fun open() {
        val p = pairing ?: return
        socket?.close(1000, null)
        _state.value = BrainState.Connecting
        val request = Request.Builder().url(p.socketUrl).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                send("hello", buildJsonObject {
                    put("role", "phone"); put("token", p.token); put("device", device)
                })
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val type = msg["type"]?.jsonPrimitive?.content ?: return
                val payload = msg["payload"]?.jsonObject ?: JsonObject(emptyMap())
                when (type) {
                    "welcome" -> {
                        _state.value = BrainState.Connected
                        retrySeconds = 1
                        send("voices.list", JsonObject(emptyMap()))
                    }
                    "voices" -> _voices.value = payload["voices"]?.jsonArray?.map { v ->
                        val o = v.jsonObject
                        Voice(
                            name = o.getValue("name").jsonPrimitive.content,
                            style = o.getValue("style").jsonPrimitive.content,
                            type = o.getValue("type").jsonPrimitive.content,
                            current = o.getValue("current").jsonPrimitive.boolean,
                        )
                    } ?: emptyList()
                }
                val id = msg["id"]?.jsonPrimitive?.content
                val waiter = id?.let { pending.remove(it) }
                if (waiter != null) waiter.complete(type to payload)
                else if (type != "welcome" && type != "voices") _pushes.tryEmit(type to payload) // e.g. live.restart, interview.answer
                onMessage(type, id, payload)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== socket) return
                if (code == 4401) {
                    _state.value = BrainState.Rejected  // wrong pairing code: don't retry
                } else {
                    _state.value = BrainState.Disconnected
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== socket) return
                _state.value = BrainState.Disconnected
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        // Requests waiting on the old connection won't get a reply.
        val lost = JsonObject(mapOf("message" to JsonPrimitive("Lost connection to the brain server")))
        pending.values.forEach { it.complete("error" to lost) }
        pending.clear()
        if (stopped) return
        val delay = retrySeconds
        retrySeconds = minOf(retrySeconds * 2, 30)
        scheduler.schedule({ if (!stopped) open() }, delay, TimeUnit.SECONDS)
    }

    fun send(type: String, payload: JsonObject, id: String = UUID.randomUUID().toString().take(12)): String {
        val envelope = JsonObject(
            mapOf<String, JsonElement>(
                "v" to JsonPrimitive(1),
                "type" to JsonPrimitive(type),
                "id" to JsonPrimitive(id),
                "ts" to JsonPrimitive(System.currentTimeMillis() / 1000.0),
                "payload" to payload,
            )
        )
        socket?.send(envelope.toString())
        return id
    }

    /** Sends a request and waits for the brain's reply with the same id. Throws BrainException on an "error" reply. */
    suspend fun request(type: String, payload: JsonObject, timeoutMs: Long = 60_000): JsonObject {
        if (_state.value != BrainState.Connected) throw BrainException("Not connected to the brain server")
        val id = UUID.randomUUID().toString().take(12)
        val reply = CompletableDeferred<Pair<String, JsonObject>>()
        pending[id] = reply
        send(type, payload, id)
        val (replyType, replyPayload) = try {
            withTimeout(timeoutMs) { reply.await() }
        } finally {
            pending.remove(id)
        }
        if (replyType == "error") {
            throw BrainException(replyPayload["message"]?.jsonPrimitive?.content ?: "Brain error")
        }
        return replyPayload
    }

    /** Fire-and-forget event for mission control (and, with source "voice", the shared memory). */
    fun event(stage: String, text: String, extra: Map<String, String> = emptyMap()) =
        send("event", buildJsonObject {
            put("stage", stage)
            put("text", text)
            put("extra", JsonObject(extra.mapValues { JsonPrimitive(it.value) }))
        })

    fun ask(prompt: String, task: String = "general"): String =
        send("llm.request", buildJsonObject { put("prompt", prompt); put("task", task) })

    suspend fun askLlm(prompt: String, task: String = "general"): JsonObject =
        request("llm.request", buildJsonObject { put("prompt", prompt); put("task", task) })

    suspend fun callTool(name: String, args: JsonObject = JsonObject(emptyMap())): JsonObject {
        val reply = request("tool.call", buildJsonObject { put("name", name); put("args", args) })
        return reply["result"]?.jsonObject ?: reply
    }

    /** Sends the key to the brain once; it's stored in the laptop's keyring and never sent back. */
    fun setKey(provider: String, key: String) =
        send("keys.set", buildJsonObject { put("provider", provider); put("key", key) })

    fun deleteKey(provider: String) =
        send("keys.delete", buildJsonObject { put("provider", provider) })

    fun updateSettings(settings: JsonObject) =
        send("settings.update", buildJsonObject { put("settings", settings) })

    /** Changes Ronie's voice everywhere; running voice sessions restart with it. */
    fun setVoice(name: String) {
        updateSettings(buildJsonObject { put("live_voice", name) })
        _voices.value = _voices.value.map { it.copy(current = it.name == name) }
    }

    fun close() {
        stopped = true
        socket?.close(1000, null)
        socket = null
    }
}
