package io.wingpane

import android.app.Application
import android.content.Intent
import android.os.Build
import io.wingpane.net.WingpaneClient
import io.wingpane.proto.Mode
import io.wingpane.uc.UcForegroundService

/** Lazily holds the single current client across Activity recreation. */
class ClientHolder<T : Any>(factory: () -> T) {
    private val create = factory
    private var instance: T? = null
    val client: T
        @Synchronized get() = instance ?: create().also { instance = it }

    @Synchronized fun clear() { instance = null }
}

class WingpaneApplication : Application() {
    val clientHolder = ClientHolder { WingpaneClient() }
    @Volatile var ucForceFgs = false
    @Volatile var foregroundServiceRunning = false
    @Volatile var currentMode: Mode = Mode.ExtendedDisplay

    fun startUcServiceIfEligible() {
        if (!clientHolder.client.isActive() || !clientHolder.client.supportsUniversalControl()) return
        if (io.wingpane.uc.WingpaneAccessibilityService.current() == null && !ucForceFgs) return
        if (foregroundServiceRunning) return
        val intent = Intent(this, UcForegroundService::class.java)
        foregroundServiceRunning = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (error: RuntimeException) {
            foregroundServiceRunning = false
            throw error
        }
    }
}
