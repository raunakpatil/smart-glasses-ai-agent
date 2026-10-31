package com.ronie.app.voice

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * "Ronie" wake word, recognised on the phone with Vosk (free, offline). Only a few words are allowed,
 * so it's quick and ignores everything else. The small Indian English model (about 36 MB) is
 * downloaded once on first use.
 */
/** What was heard with Ronie's name: the name alone ("Hey Ronie"), or the name plus a request. */
enum class Wake { NameOnly, WithRequest }

class WakeWord private constructor(model: Model) {
    private val recognizer = Recognizer(model, MIC_RATE.toFloat(), GRAMMAR).apply { setWords(true) }

    /**
     * Feed 16 kHz mono PCM; true when the user just said Ronie's name. Only settled results count, and
     * the name must be recognised with real confidence: with a tiny vocabulary, Vosk otherwise matches
     * room noise and other speech to the nearest allowed word.
     */
    fun heard(pcm: ByteArray): Wake? {
        if (!recognizer.acceptWaveForm(pcm, pcm.size)) return null
        val words = runCatching { JSONObject(recognizer.result).optJSONArray("result") }.getOrNull() ?: return null
        var best = 0.0
        var called = false
        var more = false
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            val word = w.optString("word")
            when {
                word in NAMES -> best = maxOf(best, w.optDouble("conf", 0.0))
                word in CALL_WORDS -> called = true // "hey", "oye", "sun", "suno": part of calling Ronie
                else -> more = true // anything else ([unk]) is the start of a request
            }
        }
        if (best == 0.0) return null
        // "Hey Ronie" (two words) is far less likely to come from noise or a video than a lone "Ronie".
        val needed = if (called) MIN_CONFIDENCE_CALLED else MIN_CONFIDENCE
        val named = best >= needed
        Diag.log("heard the name (confidence ${"%.2f".format(best)}, needed ${"%.2f".format(needed)}): " +
            if (named) "awake" else "ignored")
        if (!named) return null
        return if (more) Wake.WithRequest else Wake.NameOnly
    }

    companion object {
        private const val MODEL = "vosk-model-small-en-in-0.4"
        private const val MIN_CONFIDENCE = 0.6 // the user's real "Ronnie" came in at 0.67 and was missed at 0.7
        private const val MIN_CONFIDENCE_CALLED = 0.45
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL.zip"
        // Ways people (and the recogniser) say "Ronie". Anything else maps to [unk] and is ignored.
        private val NAMES = listOf("ronnie", "ronny", "rony", "roni", "ronie")
        // Wake phrases: "Ronie", "Hey Ronie", "Oye Ronie", "Sun Ronie", "Suno Ronie". Any phrase containing the
        // name wakes Ronie; listing the full phrases helps the recogniser catch them.
        private val PREFIXES = listOf("", "hey ", "oye ", "oy ", "sun ", "suno ")
        private val CALL_WORDS = PREFIXES.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        private val GRAMMAR = (PREFIXES.flatMap { p -> NAMES.map { "\"$p$it\"" } } + "\"[unk]\"")
            .joinToString(", ", "[", "]")

        /** Loads the model, downloading it first if needed. Slow the first time; call off the main thread. */
        fun load(context: Context, onProgress: (String) -> Unit = {}): WakeWord {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val dir = File(context.filesDir, MODEL)
            if (!complete(dir)) {
                dir.deleteRecursively()
                val zip = File(context.filesDir, "$MODEL.zip")
                if (!zipComplete(zip)) download(zip, onProgress)
                onProgress("Unpacking the wake word model…")
                unzip(zip, context.filesDir)
                check(complete(dir)) { "The wake word model is incomplete" }
                zip.delete()
            }
            return WakeWord(Model(dir.absolutePath))
        }

        /** The files Vosk needs; a cut-off download can leave some out. */
        private fun complete(dir: File) =
            listOf("am/final.mdl", "conf/model.conf", "graph/phones/word_boundary.int").all { File(dir, it).exists() } &&
                (File(dir, "graph/HCLr.fst").exists() || File(dir, "graph/HCLG.fst").exists())

        private const val ZIP_BYTES = 37_573_330L

        private fun zipComplete(zip: File) = zip.exists() && zip.length() == ZIP_BYTES

        /** Downloads the zip, resuming where a dropped connection stopped. */
        private fun download(zip: File, onProgress: (String) -> Unit) {
            repeat(20) {
                val have = if (zip.exists()) zip.length() else 0L
                if (have == ZIP_BYTES) return
                onProgress("Downloading the wake word model (once): ${have * 100 / ZIP_BYTES}%")
                try {
                    val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                    val resumed = conn.responseCode == 206
                    conn.inputStream.use { input ->
                        FileOutputStream(zip, resumed).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var lastReport = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                val now = System.currentTimeMillis()
                                if (now - lastReport > 2_000) {
                                    lastReport = now
                                    onProgress("Downloading the wake word model (once): ${zip.length() * 100 / ZIP_BYTES}%")
                                }
                            }
                        }
                    }
                } catch (e: java.io.IOException) {
                    Log.w("RonieWake", "Model download interrupted, resuming", e)
                }
            }
            check(zipComplete(zip)) { "Couldn't download the wake word model" }
        }

        private fun unzip(zip: File, into: File) {
            val root = into.canonicalPath
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val entry = z.nextEntry ?: break
                    val target = File(into, entry.name).canonicalFile
                    require(target.path.startsWith(root)) { "Bad path in model zip" }
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { z.copyTo(it) }
                    }
                }
            }
        }
    }
}
