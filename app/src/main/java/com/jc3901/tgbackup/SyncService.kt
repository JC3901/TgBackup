package com.jc3901.tgbackup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class SyncService : Service() {

    companion object {
        private const val ACTION_STOP = "com.jc3901.tgbackup.STOP"
        private const val CHANNEL = "sync"
        private const val NID = 1

        fun start(c: Context) {
            Tg.prefs(c).edit().putBoolean("want_running", true).apply()
            ContextCompat.startForegroundService(c, Intent(c, SyncService::class.java))
        }

        fun stop(c: Context) {
            Tg.prefs(c).edit().putBoolean("want_running", false).apply()
            c.startService(Intent(c, SyncService::class.java).setAction(ACTION_STOP))
        }
    }

    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    /** Called from Python. */
    inner class Listener {
        fun onStatus(s: String) {
            StatusBus.post(s)
            val now = System.currentTimeMillis()
            if (now - lastNotify > 1000) { lastNotify = now; notifyStatus(s) }
        }
        fun onFile(path: String) {
            MediaScannerConnection.scanFile(this@SyncService, arrayOf(path), null, null)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (worker?.isAlive == true) {
                StatusBus.post("Stopping…")
                Thread { runCatching { Tg.py().callAttr("stop") } }.start()
            } else {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NID, build("Starting backup…"), type)

        if (worker?.isAlive != true) {
            worker = Thread({ runSync() }, "tg-sync").also { it.start() }
        }
        return START_STICKY // if Android kills us in live mode, come back and resume
    }

    private fun runSync() {
        val p = Tg.prefs(this)
        val live = p.getBoolean("live", true)
        StatusBus.running = true
        StatusBus.post("Connecting to Telegram…")
        if (!live) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgBackup:sync").apply { acquire(6 * 60 * 60 * 1000L) }
        }
        try {
            if (!Tg.init(this)) {
                StatusBus.post("Not logged in — open the app and log in first.")
            } else {
                val r = Tg.py().callAttr(
                    "start_sync", p.getString("channels", ""), Tg.backupRoot(this).absolutePath, live, Listener()
                ).toString()
                if (r == "no channels") StatusBus.post("Add at least one channel first.")
                Tg.py().callAttr("wait")
            }
        } catch (e: Throwable) {
            StatusBus.post("Error: ${e.message}")
        } finally {
            wakeLock?.let { if (it.isHeld) it.release() }
            StatusBus.running = false
            StatusBus.post(StatusBus.last) // refresh buttons
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            notifyStatus(StatusBus.last, done = true)
            stopSelf()
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Backup progress", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun build(text: String, done: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (done) "Telegram backup" else "Backing up Telegram media")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
        if (!done) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, SyncService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(0, "Stop", stop)
        }
        return b.build()
    }

    private fun notifyStatus(text: String, done: Boolean = false) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NID, build(text, done)) }
    }
}
