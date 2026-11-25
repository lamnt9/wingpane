package io.wingpane.uc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.wingpane.MainActivity
import io.wingpane.R
import io.wingpane.WingpaneApplication
import io.wingpane.net.ClientState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UcForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitor: Job? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.uc_notification_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification())
        (application as WingpaneApplication).foregroundServiceRunning = true
        monitor = scope.launch {
            val app = application as WingpaneApplication
            while (true) {
                val state = app.clientHolder.client.state.value
                if (state is ClientState.Disconnected || state is ClientState.Lost ||
                    (WingpaneAccessibilityService.current() == null && !app.ucForceFgs)) {
                    stopSelf()
                    break
                }
                delay(500)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RETURN_MOUSE) {
            // TODO(WP-U6): send UcExit
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_DISPLAY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val release = PendingIntent.getService(this, 1,
            Intent(this, UcForegroundService::class.java).setAction(ACTION_RETURN_MOUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_wingpane_launcher)
            .setContentTitle(getString(R.string.uc_notification_title))
            .setContentText(getString(R.string.uc_notification_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.uc_action_display), open)
            .addAction(0, getString(R.string.uc_action_return_mouse), release)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        monitor?.cancel()
        scope.cancel()
        (application as WingpaneApplication).foregroundServiceRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "uc"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_RETURN_MOUSE = "io.wingpane.uc.RETURN_MOUSE"
        const val ACTION_DISPLAY = "io.wingpane.uc.DISPLAY"
    }
}
