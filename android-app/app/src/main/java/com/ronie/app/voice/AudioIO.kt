package com.ronie.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

const val MIC_RATE = 16_000
const val SPEAKER_RATE = 24_000
private const val ECHO_TAIL_MS = 400L
// After leaving the call link, the glasses' music link takes a moment to come back. Audio written
// before then is played on the phone's speaker, so the reply is held until the link is ready.
private const val MUSIC_LINK_READY_MS = 250L
// The glasses fade their music stream in as it starts; this much silence goes first so the fade
// happens on silence instead of Ronie's first words.
private const val FADE_IN_SILENCE_MS = 300

private val HEADSET_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)

/** The connected glasses (or other Bluetooth headset) that can carry a conversation, if any. Meta glasses first. */
fun findGlasses(devices: List<AudioDeviceInfo>): AudioDeviceInfo? {
    val headsets = devices.filter { it.type in HEADSET_TYPES }
    return headsets.firstOrNull { d -> listOf("meta", "ray-ban", "rb ").any { d.productName.toString().lowercase().contains(it) } }
        ?: headsets.firstOrNull()
}

/**
 * Routes a voice conversation: communication mode (so echo cancelling works) through the glasses when
 * they're connected, otherwise the phone's loudspeaker. Follows the glasses connecting or disconnecting.
 *
 * On the glasses, Bluetooth's call link (HFP) only carries phone-call quality sound. With [highQuality]
 * on, the call link is used while the user speaks, and Ronie's replies switch to the music link (A2DP),
 * which sounds much clearer. The cost: about half a second per switch, and no talking over Ronie.
 */
class AudioRoute(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    @Volatile private var active = false

    /** True while the conversation is on the glasses. */
    @Volatile var onGlasses = false
        private set

    /**
     * Play Ronie's replies over the glasses' music link (clearer) instead of the call link. Off by default: the music
     * link turns the glasses' mic off, so the user couldn't interrupt or say "stop" (31 Oct).
     */
    @Volatile var highQuality = false

    /** True while Ronie's reply plays over the music link; the glasses' mic is off meanwhile. */
    @Volatile var speakingMode = false
        private set

    /** When the music link is ready after a switch (elapsedRealtime). */
    @Volatile var musicReadyAt = 0L
        private set

    @Volatile private var glassesName: String? = null

    /** True while the camera handshake has the glasses' Bluetooth link to itself. */
    @Volatile private var pausedForCamera = false

    /** The glasses' music (A2DP) output, if it's connected. */
    fun musicLink(): AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && (glassesName == null || it.productName.toString() == glassesName)
    }

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { if (active) choose() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { if (active) choose() }
    }

    fun start() {
        active = true
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        choose()
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun choose() {
        val devices = am.availableCommunicationDevices
        val glasses = findGlasses(devices)
        onGlasses = glasses != null
        glassesName = glasses?.productName?.toString()
        if (pausedForCamera) return // the camera handshake has the link to itself
        if (speakingMode) return // the call link comes back in forListening()
        val target = glasses ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (target != null && am.communicationDevice?.id != target.id) am.setCommunicationDevice(target)
    }

    /** Ronie is about to talk: on the glasses, drop the call link so the reply plays over the music link. */
    fun forSpeaking() {
        if (!active || !onGlasses || !highQuality || speakingMode || pausedForCamera) return
        if (musicLink() == null) return // no music link: stay on the call link rather than fall back to the phone speaker
        musicReadyAt = SystemClock.elapsedRealtime() + MUSIC_LINK_READY_MS
        speakingMode = true
        Diag.log("audio: reply on the glasses' music link")
        am.clearCommunicationDevice()
        am.mode = AudioManager.MODE_NORMAL
    }

    /** Ronie finished: bring the call link (and the glasses' mic) back. */
    fun forListening() {
        if (!active || !speakingMode) return
        speakingMode = false
        Diag.log("audio: back to the glasses' call link")
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        choose()
    }

    /**
     * The glasses' camera can't finish its handshake while the call link (HFP/SCO) is up, so the
     * camera code drops it for a moment. The mic keeps recording; for that instant audio falls
     * back to the phone, and [resumeAfterCamera] brings the glasses back.
     */
    fun pauseForCamera() {
        if (!active || pausedForCamera) return
        pausedForCamera = true
        am.clearCommunicationDevice()
        am.mode = AudioManager.MODE_NORMAL
    }

    /** The camera handshake finished (or failed): bring the call link back if the conversation still uses it. */
    fun resumeAfterCamera() {
        if (!active || !pausedForCamera) return
        pausedForCamera = false
        if (speakingMode) return // Ronie's reply is on the music link; forListening() restores the call link
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        choose()
    }

    /** True when the user may talk over Ronie: on the glasses' call link, which cancels its own echo. */
    val canInterrupt: Boolean get() = onGlasses && !highQuality

    fun stop() {
        active = false
        speakingMode = false
        pausedForCamera = false
        am.unregisterAudioDeviceCallback(callback)
        am.clearCommunicationDevice()
        am.mode = AudioManager.MODE_NORMAL
        onGlasses = false
    }
}

private fun buildTrack(usage: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(SPEAKER_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
        )
        .setBufferSizeInBytes(
            maxOf(
                AudioTrack.getMinBufferSize(SPEAKER_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                SPEAKER_RATE / 5 * 2, // 200 ms
            )
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

/**
 * Plays Ronie's 24 kHz PCM replies, on the call track (phone loudspeaker, or the glasses' call link)
 * or the media track (the glasses' music link) as [useMedia] says. clear() stops at once on interruption.
 */
class Speaker(
    private val useMedia: () -> Boolean = { false },
    /** Where the media track must play (the glasses' music link), so it never falls back to the phone speaker. */
    private val mediaDevice: () -> AudioDeviceInfo? = { null },
    /** Don't play media before this time (elapsedRealtime): the music link is still coming up. */
    private val mediaReadyAt: () -> Long = { 0L },
) {
    private val callTrack = buildTrack(AudioAttributes.USAGE_VOICE_COMMUNICATION)
    private val mediaTrack = buildTrack(AudioAttributes.USAGE_MEDIA)
    @Volatile private var track = callTrack

    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var framesWritten = 0L
    @Volatile private var lastSoundAt = 0L
    @Volatile private var running = true
    private var loggedUnderruns = 0
    /** A byte left over from a chunk of odd length; one stray byte would turn every later sample into static. */
    private var carry: ByteArray? = null

    private val writer = thread(name = "ronie-speaker") {
        callTrack.play()
        mediaTrack.play()
        while (running) {
            val chunk = try { queue.take() } catch (e: InterruptedException) { break }
            if (chunk.isEmpty()) continue
            val next = if (useMedia()) mediaTrack else callTrack
            if (next !== track) {
                if (next === mediaTrack) mediaTrack.setPreferredDevice(mediaDevice())
                framesWritten = next.playbackHeadPosition.toLong() // count from the new track's position
                track = next
            }
            var out = chunk
            if (track === mediaTrack) {
                val wait = mediaReadyAt() - SystemClock.elapsedRealtime()
                if (wait > 0) {
                    try { Thread.sleep(wait) } catch (e: InterruptedException) { break }
                    // First audio after the switch: a little silence lets the Bluetooth stream settle, and a
                    // short fade-in avoids the click of sound starting at full level.
                    val silenceFrames = SPEAKER_RATE * FADE_IN_SILENCE_MS / 1000
                    mediaTrack.write(ByteArray(silenceFrames * 2), 0, silenceFrames * 2)
                    framesWritten += silenceFrames
                    out = fadeIn(chunk)
                }
            }
            val written = track.write(out, 0, out.size)
            if (written > 0) {
                framesWritten += written / 2
                lastSoundAt = SystemClock.elapsedRealtime()
            }
            // Gaps in the audio from the network play as clicks; log them so a crackle can be traced.
            val underruns = track.underrunCount
            if (underruns > loggedUnderruns + 2) {
                Diag.log("speaker: ${underruns - loggedUnderruns} audio gaps (${if (track === mediaTrack) "music" else "call"} link)")
                loggedUnderruns = underruns
            }
        }
    }

    @Synchronized
    fun play(pcm: ByteArray) {
        var bytes = carry?.let { it + pcm } ?: pcm
        carry = null
        if (bytes.size % 2 == 1) {
            carry = bytes.copyOfRange(bytes.size - 1, bytes.size)
            bytes = bytes.copyOf(bytes.size - 1)
        }
        queue.offer(bytes)
    }

    @Synchronized
    fun clear() {
        queue.clear()
        carry = null
        // Volume to zero first (Android ramps it) so cutting Ronie off doesn't pop.
        for (t in listOf(callTrack, mediaTrack)) t.setVolume(0f)
        SystemClock.sleep(20)
        for (t in listOf(callTrack, mediaTrack)) {
            t.pause()
            t.flush()
            t.setVolume(1f)
            t.play()
        }
        framesWritten = track.playbackHeadPosition.toLong()
    }

    /** True while Ronie is talking, plus a short tail so the room's echo isn't sent back. */
    fun busy(): Boolean {
        val unplayed = framesWritten - track.playbackHeadPosition.toLong()
        return queue.isNotEmpty() || unplayed > 0 ||
            SystemClock.elapsedRealtime() - lastSoundAt < ECHO_TAIL_MS
    }

    fun release() {
        running = false
        writer.interrupt()
        for (t in listOf(callTrack, mediaTrack)) {
            t.stop()
            t.release()
        }
    }
}

/** Ramps the first 20 ms of 16-bit PCM up from silence. */
private fun fadeIn(pcm: ByteArray): ByteArray {
    val out = pcm.copyOf()
    val rampSamples = minOf(SPEAKER_RATE / 50, out.size / 2)
    for (i in 0 until rampSamples) {
        val lo = out[2 * i].toInt() and 0xFF
        val hi = out[2 * i + 1].toInt()
        val sample = ((hi shl 8) or lo).toShort().toInt()
        val scaled = sample * i / rampSamples
        out[2 * i] = (scaled and 0xFF).toByte()
        out[2 * i + 1] = ((scaled shr 8) and 0xFF).toByte()
    }
    return out
}

/** 16 kHz mono PCM in 100 ms chunks, with the phone's echo canceller and noise suppressor on. */
@SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO before starting
class Microphone(private val onChunk: (ByteArray) -> Unit) {
    private val chunkBytes = MIC_RATE / 10 * 2
    private val record = AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MIC_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), chunkBytes * 4),
    )
    private val effects = listOfNotNull(
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId) else null,
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null,
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(record.audioSessionId) else null,
    ).onEach { it.enabled = true }

    @Volatile private var running = true

    private val reader = thread(name = "ronie-mic") {
        record.startRecording()
        val buf = ByteArray(chunkBytes)
        while (running) {
            var filled = 0
            while (filled < chunkBytes && running) {
                val n = record.read(buf, filled, chunkBytes - filled)
                if (n <= 0) break
                filled += n
            }
            if (filled == chunkBytes) onChunk(buf.copyOf())
        }
    }

    fun release() {
        running = false
        reader.join(500)
        record.stop()
        effects.forEach { it.release() }
        record.release()
    }
}
