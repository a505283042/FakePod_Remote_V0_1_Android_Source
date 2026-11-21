package com.fakepod.remote

import java.io.InputStream
import java.net.SocketTimeoutException

/** TCP is a byte stream: a read timeout does not discard a partially received frame. */
internal class WifiFrameReader {
    private var awaitingSize = false
    private var payload: ByteArray? = null
    private var used = 0

    fun pendingBytes(): Int = if (payload != null) used + 2 else if (awaitingSize) 1 else 0

    // Empty = poll timeout/no complete frame, null = EOF, nonempty = complete payload.
    fun poll(input: InputStream): ByteArray? {
        try {
            while (true) {
                val current = payload
                if (current != null) {
                    val n = input.read(current, used, current.size - used)
                    if (n < 0) return null
                    if (n == 0) return byteArrayOf()
                    used += n
                    if (used == current.size) {
                        payload = null; used = 0
                        return current
                    }
                } else {
                    val value = input.read()
                    if (value < 0) return null
                    if (awaitingSize) {
                        awaitingSize = false
                        if (value > 0) { payload = ByteArray(value); used = 0 }
                    } else if (value == 0xA5) {
                        awaitingSize = true
                    }
                }
            }
        } catch (_: SocketTimeoutException) {
            return byteArrayOf()
        }
    }
}
