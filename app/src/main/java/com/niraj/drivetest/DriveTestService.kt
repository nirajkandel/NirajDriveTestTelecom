package com.niraj.drivetest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Foreground service that keeps the drive test logging with the screen off. */
class DriveTestService : Service(), LiveMonitor.Listener {

    companion object {
        const val ACTION_START = "com.niraj.drivetest.START"
        const val ACTION_STOP = "com.niraj.drivetest.STOP"
        private const val CHANNEL = "drive_test"
        private const val NOTIF_ID = 42

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, DriveTestService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, DriveTestService::class.java).setAction(ACTION_STOP))
        }
    }

    private var acquired = false
    private var wake: PowerManager.WakeLock? = null
    private var count = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        begin()
        return START_NOT_STICKY
    }

    private fun begin() {
        createChannel()
        val n = buildNotification("Starting drive test…")
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else
                startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            // Location permission missing (Android 14+ refuses the service).
            stopSelf()
            return
        }
        if (!acquired) {
            LiveMonitor.acquire(this)
            LiveMonitor.addListener(this)
            acquired = true
        }
        if (!LiveMonitor.isRecording) LiveMonitor.startRecording(this)
        if (wake == null) {
            val pm = getSystemService(PowerManager::class.java)
            wake = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NirajDriveTest:log")?.apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        }
    }

    private fun finish() {
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        LiveMonitor.stopRecording()
        if (acquired) {
            LiveMonitor.removeListener(this)
            LiveMonitor.release()
            acquired = false
        }
        try { wake?.release() } catch (e: Exception) {}
        wake = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onSample(s: Snapshot) {
        count++
        if (count % 3 != 0) return
        val c = s.serving
        val text = buildString {
            append(LiveMonitor.recordRows).append(" samples")
            if (c != null) {
                append(" · ").append(c.rat)
                c.rsrp?.let { append(" ").append(it).append(" dBm") }
                c.pci?.let { append(" · PCI ").append(it) }
            }
            if (s.lat == null) append(" · waiting for GPS")
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Drive test", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a drive test is recording"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DriveTestService::class.java).setAction(ACTION_STOP), flags
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Drive test recording")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "Stop", stop).build())
            .build()
    }
}
