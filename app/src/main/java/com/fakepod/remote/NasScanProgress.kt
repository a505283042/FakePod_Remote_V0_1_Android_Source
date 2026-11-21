package com.fakepod.remote

/** Worker messages replace a single snapshot; they never enqueue one UI task per track. */
internal class NasScanProgress {
    data class Snapshot(val message: String, val since: Long, val done: Long?, val total: Long?)
    private var current = Snapshot("", 0, null, null)
    @Synchronized fun reset(message: String, now: Long) { current = Snapshot(message, now, null, null) }
    @Synchronized fun offer(message: String, now: Long) {
        val counts = COUNTS.find(message)
        current = Snapshot(message, if (message == current.message) current.since else now,
            counts?.groupValues?.get(1)?.toLongOrNull() ?: current.done,
            counts?.groupValues?.get(2)?.toLongOrNull() ?: current.total)
    }
    @Synchronized fun snapshot(): Snapshot = current
    companion object { private val COUNTS = Regex("^读取歌曲信息 ([0-9]+)/([0-9]+)") }
}
