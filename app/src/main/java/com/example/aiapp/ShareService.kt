package com.example.aiapp

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app running while this phone is shared: a foreground service (so Android doesn't stop the app when
 * the screen is off) with a notification that has a Stop button. The sharing itself is [HostRuntime].
 */
class ShareService : Service() {
    companion object {
        private const val ACTION_STOP = "com.example.aiapp.action.STOP_SHARING"
        private const val CHANNEL_ID = "sharing"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_MS = 10L * 60L * 60L * 1000L

        /** Starts sharing (the settings are read by the service). */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, ShareService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ShareService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android wants this within a few seconds of startForegroundService().
        startInForeground()
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val config = readHostConfig(prefs)
        if (config == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        takeWakeLock()
        HostRuntime.start(applicationContext, config, HostStats.shared(prefs))
        // When sharing can't go on until something is fixed (wrong token, wrong address), there is nothing left to keep alive.
        watching?.cancel()
        watching = watcherScope.launch {
            HostRuntime.state.collect { if (it is HostState.Failed) stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watching?.cancel()
        watcherScope.cancel()
        HostRuntime.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sharing this phone", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 0, it, flags) }
        val stop = PendingIntent.getService(this, 1, Intent(this, ShareService::class.java).setAction(ACTION_STOP), flags)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle("Sharing this phone")
            .setContentText("Zonk-Core is answering chats through your relay.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_share), "Stop sharing", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun takeWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zonk-core:sharing").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MS)
        }
    }
}
