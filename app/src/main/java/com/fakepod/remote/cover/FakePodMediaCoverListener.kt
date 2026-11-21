package com.fakepod.remote.cover

import android.app.Notification
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.zip.CRC32

/**
 * User-authorized Android MediaSession artwork observer.
 * QQ Music is preferred among active sessions, but generic players work too.
 * No QQ private API, storage scraping or third-party account credentials.
 */
class FakePodMediaCoverListener : NotificationListenerService() {
    companion object {
        private var current: WeakReference<FakePodMediaCoverListener>? = null
        fun refreshIfRunning() {
            val owner = current?.get() ?: return
            owner.main.post { owner.refresh() }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var manager: MediaSessionManager
    private var observed: MediaController? = null
    private var revision = 0L
    private var stableKey = ""
    private var deliveredKey = ""
    private var lastInspect = 0L
    private var lastPosition = -1L
    private var inferredTrack = 0L

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = inspect(metadata)
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() = refresh()
    }
    private val sessionsCallback =
        MediaSessionManager.OnActiveSessionsChangedListener { main.post { refresh() } }

    override fun onListenerConnected() {
        super.onListenerConnected()
        current = WeakReference(this)
        manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        try {
            manager.addOnActiveSessionsChangedListener(sessionsCallback,
                ComponentName(this, FakePodMediaCoverListener::class.java), main)
            refresh()
        } catch (_: SecurityException) {
            FakePodCoverHub.mediaStatus("需要授予通知访问权限，无法读取媒体会话")
        }
    }

    override fun onListenerDisconnected() {
        release()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        release()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.notification?.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true)
            main.post { refresh() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.notification?.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true)
            main.post { refresh() }
    }

    private fun release() {
        ++revision
        observed?.unregisterCallback(mediaCallback)
        observed = null
        if (::manager.isInitialized) {
            try { manager.removeOnActiveSessionsChangedListener(sessionsCallback) }
            catch (_: Exception) { }
        }
        if (current?.get() === this) current = null
    }

    private fun refresh() {
        if (!FakePodCoverHub.isEnabled() || !::manager.isInitialized) return
        val all = try {
            manager.getActiveSessions(ComponentName(this, FakePodMediaCoverListener::class.java))
        } catch (_: SecurityException) { emptyList() }
        val active = all.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        // Do not transfer artwork from an ambiguous second app.
        val target = active.firstOrNull { it.packageName == "com.tencent.qqmusic" }
            ?: active.singleOrNull()
        if (observed?.sessionToken != target?.sessionToken) {
            ++revision
            observed?.unregisterCallback(mediaCallback)
            observed = target
            stableKey = ""
            deliveredKey = ""
            lastPosition = -1L
            inferredTrack = 0L
            target?.registerCallback(mediaCallback, main)
            if (target == null) FakePodCoverHub.clearArtwork()
        }
        if (target != null) inspect(target.metadata)
    }

    private fun inspect(metadata: MediaMetadata?) {
        val controller = observed ?: return
        if (!FakePodCoverHub.isEnabled() ||
            controller.playbackState?.state != PlaybackState.STATE_PLAYING) return
        if (metadata == null) {
            FakePodCoverHub.mediaStatus(controller.packageName + " 已播放，但未提供媒体信息")
            return
        }

        val mediaBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)

        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val position = controller.playbackState?.position ?: -1L
        // Some QQ Music versions publish real-time lyrics in TITLE. Ignore TITLE in
        // artwork identity; use media ID and/or album, artist, duration and time reset.
        if (lastPosition >= 15_000L && position in 0L..8_000L &&
            position < lastPosition - 8_000L) ++inferredTrack
        if (position >= 0L) lastPosition = position
        val trackId = controller.packageName + "|" +
            mediaId.ifEmpty { album + "|" + artist + "|" + duration + "|" + inferredTrack }
        val now = SystemClock.elapsedRealtime()
        if (trackId == stableKey && now - lastInspect < 15_000L) return
        stableKey = trackId
        lastInspect = now
        val serial = ++revision
        val token = controller.sessionToken
        val appName = if (controller.packageName == "com.tencent.qqmusic")
            "QQ音乐" else controller.packageName

        try {
            worker.execute {
                // Bitmap is a system-owned object: never recycle it here.
                val image = mediaBitmap ?: readArtworkUri(artUri.orEmpty())
                    ?: loadNotificationArtwork(controller.packageName)
                if (image == null) {
                    FakePodCoverHub.mediaStatus(appName + " · MediaSession 已检测，但未公开封面")
                    return@execute
                }
                val dimension = image.width.toString() + "×" + image.height
                val jpeg = encodeJpeg(image)
                if (jpeg == null) {
                    FakePodCoverHub.mediaStatus(appName + " · JPEG 压缩失败或图片太大")
                    return@execute
                }
                val crc = CRC32().apply { update(jpeg) }.value
                val key = trackId + "|" + crc
                main.post {
                    if (serial != revision || observed?.sessionToken != token) return@post
                    if (deliveredKey == key) return@post
                    deliveredKey = key
                    FakePodCoverHub.artworkReady(jpeg, key, appName, dimension)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    private fun readArtworkUri(text: String): Bitmap? {
        if (text.isBlank()) return null
        return try {
            val uri = Uri.parse(text)
            if (uri.scheme != "content" && uri.scheme != "android.resource") return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val step = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 800)
            val opt = BitmapFactory.Options().apply { inSampleSize = step }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
        } catch (_: Exception) { null }
    }

    private fun loadNotificationArtwork(packageName: String): Bitmap? {
        return try {
            val n = activeNotifications?.firstOrNull {
                it.packageName == packageName &&
                    it.notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
            }?.notification ?: return null
            val drawable = n.getLargeIcon()?.loadDrawable(this) ?: return null
            if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
            val w = drawable.intrinsicWidth.coerceIn(1, 800)
            val h = drawable.intrinsicHeight.coerceIn(1, 800)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(Canvas(bitmap))
            bitmap
        } catch (_: Exception) { null }
    }

    private fun encodeJpeg(input: Bitmap): ByteArray? {
        try {
            var limit = 400
            while (limit >= 100) {
                val ratio = minOf(1f, limit.toFloat() / maxOf(input.width, input.height))
                val width = maxOf(1, (input.width * ratio).toInt())
                val height = maxOf(1, (input.height * ratio).toInt())
                val scaled = Bitmap.createScaledBitmap(input, width, height, true)
                for (quality in intArrayOf(78, 65, 50, 35)) {
                    val bytes = ByteArrayOutputStream(48 * 1024)
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, bytes)) continue
                    val data = bytes.toByteArray()
                    if (data.size in 32..FakePodCoverPackets.MAX_JPEG_BYTES &&
                        FakePodCoverPackets.isJpeg(data)) {
                        if (scaled !== input) scaled.recycle()
                        return data
                    }
                }
                if (scaled !== input) scaled.recycle()
                limit /= 2
            }
        } catch (_: Exception) { }
        catch (_: OutOfMemoryError) { }
        return null
    }
}
