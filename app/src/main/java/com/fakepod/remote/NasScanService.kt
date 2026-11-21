package com.fakepod.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Process-lifetime guard for the user-started scanner. A killed process resumes from its journal on rescan. */
class NasScanService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var lastNotification = 0L

    override fun onCreate() {
        super.onCreate()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "NAS 曲库扫描", NotificationManager.IMPORTANCE_LOW))
            val notification = notification("正在连接 NAS…")
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, notification)
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fakepod:nas-scan").apply {
                setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
            }
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fakepod:nas-scan").apply {
                    setReferenceCounted(false); acquire()
                }
            active = this
        } catch (e: Exception) {
            failure = e
            stopSelf()
        } finally { ready.countDown() }
    }

    private fun notification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)!!.apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("FakePod · NAS 曲库更新").setContentText(text.take(180))
            .setStyle(Notification.BigTextStyle().bigText(text.take(500))).setOngoing(true)
            .setOnlyAlertOnce(true).setContentIntent(PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
    }

    private fun progress(text: String, immediate: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!immediate && now - lastNotification < 1000) return
        lastNotification = now
        getSystemService(NotificationManager::class.java).notify(ID, notification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) {
        onCancel?.invoke()
        stopSelf()
    }
    override fun onDestroy() {
        active = null
        try { if (wifi?.isHeld == true) wifi?.release() } finally {
            if (wake?.isHeld == true) wake?.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "nas_scan"
        private const val ID = 525
        @Volatile private var active: NasScanService? = null
        @Volatile private var failure: Exception? = null
        @Volatile private var ready = CountDownLatch(1)
        @Volatile private var onCancel: (() -> Unit)? = null
        fun start(context: Context, cancel: () -> Unit) {
            check(active == null) { "已有 NAS 扫描任务运行" }
            failure = null; ready = CountDownLatch(1); onCancel = cancel
            context.startForegroundService(Intent(context, NasScanService::class.java))
        }
        fun awaitReady() {
            check(ready.await(15, TimeUnit.SECONDS)) { "后台扫描服务启动超时" }
            failure?.let { throw IllegalStateException("后台扫描服务启动失败", it) }
            check(active != null) { "后台扫描服务未启动" }
        }
        fun update(text: String, immediate: Boolean = false) { active?.progress(text, immediate) }
        fun stop(context: Context) {
            onCancel = null
            context.stopService(Intent(context, NasScanService::class.java))
        }
    }
}
