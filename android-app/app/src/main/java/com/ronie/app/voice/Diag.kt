package com.ronie.app.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small log of what Ronie's voice did (woke, slept, goodbye heard, wake word loaded or not), kept in the app's
 * files so it survives Android's log rotation: `adb shell run-as com.ronie.app cat files/ronie.log`.
 */
object Diag {
    private var file: File? = null
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "ronie.log")
    }

    @Synchronized
    fun log(message: String) {
        Log.d("RonieWake", message)
        val f = file ?: return
        runCatching {
            if (f.length() > 64_000) f.writeText(f.readLines().takeLast(300).joinToString("\n") + "\n")
            f.appendText("${time.format(Date())} $message\n")
        }
    }
}
