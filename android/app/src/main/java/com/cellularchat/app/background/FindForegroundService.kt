package com.cellularchat.app.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.cellularchat.app.FindController
import com.cellularchat.app.R

/**
 * Visible, time-limited Find foreground service (IMPLEMENTATION_PLAN.md §8,
 * PROTOCOL_V2.md §10). Runs with `connectedDevice` type, shows a notification
 * with a Stop action, and enforces a hard deadline: at the deadline or on Stop
 * it expires all discovery and radio work. The session lives here, not in the
 * Activity, so it survives the UI going away.
 */
class FindForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var expiry: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                FindController.stop(applicationContext)
                stopSelfSafely()
                return START_NOT_STICKY
            }
        }
        val alias = intent?.getStringExtra(EXTRA_ALIAS) ?: "상대"
        val deadline = intent?.getLongExtra(EXTRA_DEADLINE, 0L) ?: 0L
        startForegroundCompat(alias)
        scheduleExpiry(deadline)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(alias: String) {
        ensureChannel()
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FindForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.find_notification_title))
            .setContentText(getString(R.string.find_notification_text, alias))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.find_stop),
                    stopIntent,
                ).build(),
            )
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun scheduleExpiry(deadlineMillis: Long) {
        expiry?.let { handler.removeCallbacks(it) }
        if (deadlineMillis <= 0) return
        val delay = deadlineMillis - System.currentTimeMillis()
        val runnable = Runnable {
            FindController.expire(applicationContext)
            stopSelfSafely()
        }
        expiry = runnable
        handler.postDelayed(runnable, delay.coerceAtLeast(0))
    }

    private fun stopSelfSafely() {
        expiry?.let { handler.removeCallbacks(it) }
        expiry = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        expiry?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Find", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "cellfind.find"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.cellularchat.app.action.STOP_FIND"
        private const val EXTRA_ALIAS = "alias"
        private const val EXTRA_DEADLINE = "deadline"

        fun start(context: Context, alias: String, deadlineMillis: Long) {
            val intent = Intent(context, FindForegroundService::class.java)
                .putExtra(EXTRA_ALIAS, alias)
                .putExtra(EXTRA_DEADLINE, deadlineMillis)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FindForegroundService::class.java))
        }

        @Suppress("unused")
        private fun uptime(): Long = SystemClock.elapsedRealtime()
    }
}
