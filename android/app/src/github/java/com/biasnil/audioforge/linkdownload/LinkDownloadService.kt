package com.biasnil.audioforge.linkdownload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.biasnil.audioforge.MainActivity
import com.biasnil.audioforge.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the app running while a download from a link is in progress (as
 * Media Downloader Toolkit's DownloadService does): a progress notification
 * with Stop, and the CPU kept awake, so it carries on with the screen off or
 * in other apps.
 */
class LinkDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LinkDownloads.cancel()
            return START_NOT_STICKY
        }
        // Android requires startForeground() promptly, so it comes first.
        ensureChannel(this)
        startForeground(NOTIFICATION_ID, progressNotification(LinkDownloads.job.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!LinkDownloads.isRunning) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioForge:linkdownload")
                .apply { acquire(WAKE_LOCK_CAP_MS) }
        }
        scope.launch {
            // At most about once a second, so a fast download doesn't flood the notification.
            LinkDownloads.job.collect { job ->
                if (job != null && !job.finished) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, progressNotification(job))
                }
                delay(1_000)
            }
        }
        return START_NOT_STICKY
    }

    // Android 15+ limits this kind of service to 6 hours a day.
    override fun onTimeout(startId: Int, fgsType: Int) {
        LinkDownloads.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun progressNotification(job: LinkJob?): android.app.Notification {
        val text = job?.let { getString(R.string.link_progress, it.done + it.failed + 1, it.total, it.current.orEmpty()) }.orEmpty()
        val progress = job?.progress ?: 0f
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.link_downloading))
            .setContentText(text)
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(
                0, getString(R.string.link_stop),
                PendingIntent.getService(
                    this, 1,
                    Intent(this, LinkDownloadService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "link_downloads"
        private const val NOTIFICATION_ID = 0x4C44 // "LD"
        private const val ACTION_STOP = "com.biasnil.audioforge.LINK_DOWNLOAD_STOP"
        private const val WAKE_LOCK_CAP_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LinkDownloadService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkDownloadService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.link_channel), NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
