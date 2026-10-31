package com.ronie.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Ronie's own wake and sleep sounds: two soft bell-like notes, rising when Ronie starts listening and falling when it
 * goes back to waiting for its name. Made here from sine tones, so there are no audio files. Played on the call
 * stream, which is the one routed to the glasses while Ronie listens.
 */
class Chime {
    private val wake = render(listOf(NOTE_E5 to 0.0, NOTE_B5 to 0.085))
    private val sleep = render(listOf(NOTE_B5 to 0.0, NOTE_E5 to 0.11))
    private var track: AudioTrack? = null

    fun wake() = play(wake)
    fun sleep() = play(sleep)

    @Synchronized
    private fun play(pcm: ShortArray) {
        track?.release()
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also {
                it.write(pcm, 0, pcm.size)
                it.play()
            }
    }

    @Synchronized
    fun release() {
        track?.release()
        track = null
    }

    private companion object {
        const val RATE = 24_000
        const val NOTE_E5 = 659.25
        const val NOTE_B5 = 987.77
        const val LENGTH_S = 0.42
        const val VOLUME = 0.32

        /** Notes (frequency, start second) with a quick attack and a soft bell-like decay, plus a quiet octave. */
        fun render(notes: List<Pair<Double, Double>>): ShortArray {
            val n = (RATE * LENGTH_S).toInt()
            val out = DoubleArray(n)
            for ((freq, start) in notes) {
                val first = (start * RATE).toInt()
                for (i in first until n) {
                    val t = (i - first).toDouble() / RATE
                    val envelope = minOf(1.0, t / 0.006) * exp(-t * 9.0)
                    out[i] += envelope * (sin(2 * PI * freq * t) + 0.25 * sin(4 * PI * freq * t))
                }
            }
            val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
            return ShortArray(n) { i ->
                val fadeOut = minOf(1.0, (n - i).toDouble() / (RATE * 0.02)) // no click at the end
                (out[i] / peak * VOLUME * fadeOut * Short.MAX_VALUE).toInt().toShort()
            }
        }
    }
}
