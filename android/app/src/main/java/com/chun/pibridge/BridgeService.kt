package com.chun.pibridge

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store by lazy { BridgeStore.get(this) }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("connection", "SSH接続", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel("agent", "エージェントの状態", NotificationManager.IMPORTANCE_DEFAULT)
        )
        // Chat continuity across devices is remoteMessaging on API 34+.
        // dataSync is time-limited to six hours on target 35+ and cannot provide day-long
        // messaging.
        if (android.os.Build.VERSION.SDK_INT >= 34)
            startForeground(
                1,
                notification("SSH接続を準備中"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        else if (android.os.Build.VERSION.SDK_INT >= 29)
            startForeground(
                1,
                notification("SSH接続を準備中"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        else startForeground(1, notification("SSH接続を準備中"))
        store.notifyState = { key, state ->
            val session = sessionForNotification(store.state.value.sessions, key)
            val n = agentNotification(this, session, state, openIntent())
            runCatching { nm.notify(key.hashCode(), n) }
        }
        scope.launch { store.state.collect { nm.notify(1, notification(it.status)) } }
    }

    private fun openIntent() =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun notification(text: String) =
        NotificationCompat.Builder(this, "connection")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Pi Bridge")
            .setContentText(text)
            .setContentIntent(openIntent())
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "disconnect") {
            store.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra("profileId")
        store.profiles.all().find { it.id == id }?.let {
            if (!store.isConnectingTo(it)) store.connect(it)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        store.disconnect()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        store.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
