package com.fakepod.remote.cover

/** FakePod_S31 BLE Command characteristic: 0x40 BEGIN, 0x41 DATA, 0x42 FINISH. */
object FakePodCoverPackets {
    const val MAX_JPEG_BYTES = 192 * 1024
    const val MAX_GATT_PACKET_BYTES = 244

    fun packetLimit(mtu: Int): Int = (mtu - 3).coerceIn(20, MAX_GATT_PACKET_BYTES)

    fun isJpeg(jpeg: ByteArray): Boolean = jpeg.size >= 4 &&
        jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte() &&
        jpeg[jpeg.size - 2] == 0xFF.toByte() && jpeg.last() == 0xD9.toByte()

    fun begin(tx: Int, jpeg: ByteArray): ByteArray {
        require(tx in 0..255 && jpeg.size in 32..MAX_JPEG_BYTES && isJpeg(jpeg))
        val n = jpeg.size
        return byteArrayOf(0x40, tx.toByte(), n.toByte(), (n ushr 8).toByte(),
            (n ushr 16).toByte(), (n ushr 24).toByte())
    }

    fun data(tx: Int, offset: Int, jpeg: ByteArray, maxFrame: Int): ByteArray {
        require(tx in 0..255 && offset in 0 until jpeg.size)
        require(maxFrame in 20..MAX_GATT_PACKET_BYTES)
        val count = minOf(jpeg.size - offset, maxFrame - 6)
        return byteArrayOf(0x41, tx.toByte(), offset.toByte(), (offset ushr 8).toByte(),
            (offset ushr 16).toByte(), (offset ushr 24).toByte()) +
            jpeg.copyOfRange(offset, offset + count)
    }

    fun finish(tx: Int): ByteArray {
        require(tx in 0..255)
        return byteArrayOf(0x42, tx.toByte())
    }
}
