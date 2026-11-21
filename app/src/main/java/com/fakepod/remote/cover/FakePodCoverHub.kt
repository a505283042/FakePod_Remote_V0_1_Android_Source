package com.fakepod.remote.cover

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Opt-in album artwork bridge. Never opens its own GATT connection. Retains a
 * JPEG in memory so it can be re-sent after reconnect/MTU negotiation.
 */
object FakePodCoverHub {
    private const val PREFS = "fakepod_remote"
    private const val PREF_ENABLED = "phone_cover_sync_enabled"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var enabled = false
    @Volatile private var statusText = "手机封面同步未开启"
    private var uploader: FakePodCoverUploader? = null
    private var latest: ByteArray? = null
    private var lastKey: String? = null
    private var generation = 0L

    fun initialize(context: Context) {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, false)
        if (enabled) statusText = "已开启，等待 QQ 音乐 / 系统播放器"
    }

    fun isEnabled(): Boolean = enabled
    fun status(): String = statusText

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_ENABLED, value).apply()
        enabled = value
        main.post {
            if (value) {
                statusText = "已开启，正在检测 QQ 音乐 / 系统播放器"
                FakePodMediaCoverListener.refreshIfRunning()
            } else {
                ++generation
                lastKey = null
                latest = null
                uploader?.cancel()
                statusText = "手机封面同步已关闭"
            }
        }
    }

    fun bindToExistingBle(sender: FakePodCoverUploader) {
        main.post {
            uploader = sender
            if (enabled) latest?.let { queue(it, generation) }
        }
    }

    fun unbindFromBle() {
        main.post {
            uploader?.cancel()
            uploader = null
            if (enabled && latest != null) statusText = "封面已获取，等待 FakePod BLE 连接"
        }
    }

    fun mediaStatus(text: String) {
        main.post { if (enabled) statusText = text }
    }

    fun artworkReady(jpeg: ByteArray, artKey: String, appName: String, pixels: String) {
        main.post {
            if (!enabled || lastKey == artKey) return@post
            lastKey = artKey
            latest = jpeg.clone()
            val serial = ++generation
            statusText = appName + " · " + pixels + " · JPEG " + (jpeg.size / 1024) + " KiB"
            queue(jpeg, serial)
        }
    }

    private fun queue(jpeg: ByteArray, serial: Long) {
        val sender = uploader
        if (sender == null) {
            statusText = "图片已获取，等待 FakePod BLE 连接"
            return
        }
        statusText = "正在通过 BLE 上传封面 (" + (jpeg.size / 1024) + " KiB)…"
        sender.upload(jpeg) { ok ->
            if (serial != generation || !enabled) return@upload
            statusText = if (ok) "BLE 分片写入完成，等待 FakePod 显示"
                else "BLE 封面上传中断，请检查设备是否在蓝牙接收模式"
        }
    }

    fun clearArtwork() {
        main.post {
            ++generation
            lastKey = null
            latest = null
            uploader?.cancel()
            if (enabled) statusText = "等待 QQ 音乐 / 系统播放器开始播放"
        }
    }
}
