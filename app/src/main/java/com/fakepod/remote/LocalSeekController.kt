package com.fakepod.remote

internal data class LocalSeekContext(
    val connected: Boolean, val allowed: Boolean, val track: Long?,
    val playbackRevision: Long, val seekRevision: Long, val lastTargetMs: Long,
    val durationMs: Long, val statusSerial: Long, val feedbackSerial: Long,
    val playbackState: Int, val musicSource: Boolean = true, val nasSource: Boolean = false
)
internal data class LocalSeekRequest(
    val track: Long, val playbackRevision: Long, val seekRevision: Long,
    val targetMs: Long, val durationMs: Long, val nasSource: Boolean = false
)

/** Pure UI transaction: no commands while dragging; completion requires device feedback. */
internal class LocalSeekController {
    private var drag: LocalSeekRequest? = null
    private var pending: LocalSeekRequest? = null
    private var deadline = 0L
    val dragging get() = drag != null
    val waiting get() = pending != null
    val previewMs: Long? get() = drag?.targetMs ?: pending?.targetMs

    private fun same(context: LocalSeekContext, request: LocalSeekRequest): Boolean =
        context.connected && context.musicSource && context.nasSource == request.nasSource && context.track == request.track && context.playbackRevision == request.playbackRevision

    fun begin(context: LocalSeekContext, positionMs: Long): Boolean {
        if (!context.connected || !context.musicSource || !context.allowed || context.track == null || context.durationMs <= 0 || waiting) return false
        drag = LocalSeekRequest(context.track, context.playbackRevision, context.seekRevision,
            positionMs.coerceIn(0L, context.durationMs - 1), context.durationMs, context.nasSource)
        return true
    }
    fun move(progress: Int) {
        val current = drag ?: return
        val target = (current.durationMs * progress.coerceIn(0, 1000) / 1000).coerceAtMost(current.durationMs - 1)
        drag = current.copy(targetMs = target)
    }
    fun release(context: LocalSeekContext): LocalSeekRequest? {
        val current = drag ?: return null
        drag = null
        if (!context.allowed || !same(context, current) || context.seekRevision != current.seekRevision || context.durationMs <= 0) return null
        return current.copy(targetMs = current.targetMs.coerceAtMost(context.durationMs - 1), durationMs = context.durationMs)
    }
    fun submitted(request: LocalSeekRequest, now: Long) { pending = request; deadline = now + 10_000L }
    // true only once when the device did not confirm before the deadline.
    fun observe(context: LocalSeekContext, now: Long): Boolean {
        drag?.let { if (!context.allowed || !same(context, it) || context.seekRevision != it.seekRevision) drag = null }
        val current = pending ?: return false
        if (!same(context, current) || context.playbackState in listOf(7, 8, 9)) { pending = null; return false }
        if (context.seekRevision != current.seekRevision) {
            if (context.lastTargetMs != current.targetMs ||
                (context.statusSerial > context.feedbackSerial && context.playbackState != 5)) pending = null
        }
        if (pending != null && now >= deadline) { pending = null; return true }
        return false
    }
    fun cancelDrag() { drag = null }
    fun cancel() { drag = null; pending = null }
}

internal data class LocalSeekFeedback(val flags: Int, val track: Long?, val playbackRevision: Long,
    val seekRevision: Long, val lastTargetMs: Long)
internal object LocalSeekWire {
    private fun read(data: ByteArray, at: Int): Long = (0..3).fold(0L) { v, i ->
        v or ((data[at+i].toLong() and 255L) shl (8*i))
    }
    fun decode(data: ByteArray): LocalSeekFeedback? {
        if (data.size != 19 || data[0].toInt() != 5 || data[1].toInt() != 1) return null
        return LocalSeekFeedback(data[2].toInt() and 3,
            read(data,3).let { if (it == 0xFFFF_FFFFL) null else it },
            read(data,7), read(data,11), read(data,15))
    }
    fun encode(request: LocalSeekRequest): ByteArray? {
        if (request.track !in 0L..0xFFFF_FFFEL || request.playbackRevision !in 1L..0xFFFF_FFFFL ||
            request.seekRevision !in 0L..0xFFFF_FFFFL || request.targetMs !in 0L..0xFFFF_FFFFL ||
            request.targetMs >= request.durationMs) return null
        val bytes = ByteArray(17); bytes[0] = 0x18
        listOf(request.track, request.playbackRevision, request.seekRevision, request.targetMs).forEachIndexed { index, value ->
            repeat(4) { i -> bytes[1+index*4+i] = (value ushr (8*i)).toByte() }
        }
        return bytes
    }
}
