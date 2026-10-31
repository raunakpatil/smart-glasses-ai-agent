package com.ronie.app.voice

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Watches for the glasses' Bluetooth audio (mic and speakers) connecting and disconnecting. */
class GlassesMonitor(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val _name = MutableStateFlow<String?>(null)

    /** The connected glasses' name, or null. */
    val name: StateFlow<String?> = _name

    init {
        refresh()
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh()
        }, Handler(Looper.getMainLooper()))
    }

    private fun refresh() {
        _name.value = findGlasses(am.availableCommunicationDevices)?.productName?.toString()
    }
}
