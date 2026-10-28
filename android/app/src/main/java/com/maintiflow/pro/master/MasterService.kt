package com.maintiflow.pro.master

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.maintiflow.pro.MainActivity
import com.maintiflow.pro.MaintiFlowApp
import com.maintiflow.pro.R

/**
 * Service de premier plan qui garde le serveur actif quand l'écran est éteint ou l'application fermée.
 * Une notification permanente indique que ce téléphone est le serveur.
 */
class MasterService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        private const val CHANNEL = "master_server"
        private const val NOTIF_ID = 8000

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, MasterService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, MasterService::class.java))
    }

    private val controller get() = (application as MaintiFlowApp).container.master

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.master_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val address = controller.addresses().firstOrNull() ?: MasterController.LOCAL_URL
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.master_notification_title))
            .setContentText(getString(R.string.master_notification_text, address))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        acquireLocks()
        controller.startServer()
        if (!controller.state.value.running) stopSelf()
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MaintiFlow:serveur").apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = runCatching {
                (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(mode, "MaintiFlow:serveur").apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
    }

    override fun onDestroy() {
        controller.stopServer()
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }
}

/** Relance le serveur après un redémarrage du téléphone, si l'option est activée. */
class MasterBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val master = (context.applicationContext as MaintiFlowApp).container.master
        if (master.autoStart && runCatching { master.isInitialized() }.getOrDefault(false)) MasterService.start(context)
    }
}
