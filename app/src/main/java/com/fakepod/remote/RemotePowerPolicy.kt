package com.fakepod.remote

/** Bounded discovery retries; a manual retry/network change resets the sequence. */
internal object RemotePowerPolicy {
    const val BACKGROUND_GRACE_MS = 30_000L
    const val LIBRARY_POLL_MS = 5_000L
    fun retryDelay(failures: Int): Long = minOf(15_000L, 700L shl failures.coerceIn(0, 5))
}
