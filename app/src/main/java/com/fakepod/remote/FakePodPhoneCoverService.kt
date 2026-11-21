package com.fakepod.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.fakepod.remote.cover.FakePodCoverHub

/**
 * Only runs while the user has opted in to phone album-art synchronization.
 * Keeps the existing application-owned BLE GATT session alive when the user
 * switches to QQ Music. Does not create a second GATT connection.
 */
class FakePodPhoneCoverService : Service() {
    companion object {
        private const val TAG = "FakePodCoverSync"
        private const val CHANNEL = "fakepod_phone_cover"
        private const val NOTIFICATION_ID = 2816
        @Volatile var isRunning = false
            private set

        fun start(context: Context) {
            if (!FakePodCoverHub.isEnabled()) return
            try {
                val intent = Intent(context, FakePodPhoneCoverService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (error: Exception) {
                Log.w(TAG, "前台 BLE 封面服务启动失败", error)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FakePodPhoneCoverService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channels = getSystemService(NotificationManager::class.java)
        channels?.createNotificationChannel(
            NotificationChannel(CHANNEL, "FAKEPOD 手机封面同步",
                NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("FAKEPOD 手机封面同步")
            .setContentText("正在读取已授权的音乐封面并保持 BLE 遥控连接")
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true
        (application as FakePodApplication).bleClient.setRemoteForeground(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!FakePodCoverHub.isEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }
}
