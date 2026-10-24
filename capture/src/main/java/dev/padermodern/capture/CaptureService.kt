package dev.padermodern.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { CaptureHub.stop(); stopSelf(); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Bus-Capture", NotificationManager.IMPORTANCE_LOW),
        )
        ServiceCompat.startForeground(this, ID, build(this, "Aufnahme läuft"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        // a killed process must not continue a session silently
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "capture"
        private const val ID = 4711
        private const val STOP = "dev.padermodern.capture.STOP"

        private fun build(context: Context, text: String): Notification {
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(context, 1, Intent(context, CaptureService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Bus-Capture").setContentText(text)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(0, "Beenden", stop).build()
        }

        fun notify(context: Context, text: String) {
            runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, build(context, text)) }
        }
    }
}
