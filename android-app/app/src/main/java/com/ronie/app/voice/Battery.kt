package com.ronie.app.voice

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager

/** Battery of the glasses (what Bluetooth reports) and of the phone. */
object Battery {
    fun phone(context: Context): String {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (bm.isCharging) "$level%, charging" else "$level%"
    }

    /** (glasses name, percent) or null if no glasses are paired; percent is -1 when they don't report it. */
    fun glasses(context: Context): Pair<String, Int>? {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        val devices = try { adapter.bondedDevices } catch (_: SecurityException) { return null }
        val glasses = devices.firstOrNull { d ->
            val name = (d.name ?: "").lowercase()
            listOf("meta", "ray-ban", "rb ").any { name.contains(it) }
        } ?: return null
        // Android keeps the level a headset reports, but only exposes it through this unlisted method.
        val level = runCatching { glasses.javaClass.getMethod("getBatteryLevel").invoke(glasses) as Int }.getOrDefault(-1)
        return (glasses.name ?: "glasses") to level
    }

    fun describe(context: Context): Map<String, String> {
        val g = glasses(context)
        return mapOf(
            "glasses" to when {
                g == null -> "no glasses paired with the phone"
                g.second < 0 -> "${g.first}: not connected or not reporting battery right now"
                else -> "${g.first}: ${g.second}%"
            },
            "phone" to phone(context),
        )
    }
}
