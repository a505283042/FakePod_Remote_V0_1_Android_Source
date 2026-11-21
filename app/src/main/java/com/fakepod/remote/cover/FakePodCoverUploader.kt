package com.fakepod.remote.cover

/**
 * Uses the existing serialized encrypted GATT Command writer. Each completion
 * callback must be delivered by the matching onCharacteristicWrite event.
 */
interface FakePodCoverTransport {
    val maxPacketBytes: Int
    fun writeCoverPacket(packet: ByteArray, done: (Boolean) -> Unit)
    // Raise BLE connection priority only while transmitting JPEG chunks.
    fun onTransferActive(active: Boolean) {}
    fun onTransferFinished(ok: Boolean, bytes: Int, packets: Int, elapsedMs: Long) {}
}

/** Single-looper state machine, latest album art wins over an in-progress transfer. */
class FakePodCoverUploader(
    private val transport: FakePodCoverTransport,
    private val dispatch: (() -> Unit) -> Unit
) {
    private enum class Phase { BEGIN, DATA, FINISH }
    private data class Work(
        val serial: Long,
        val tx: Int,
        val jpeg: ByteArray,
        val complete: (Boolean) -> Unit,
        var offset: Int = 0,
        var phase: Phase = Phase.BEGIN,
        val startedNs: Long = System.nanoTime(),
        var packetCount: Int = 0
    )
    private var sequence = 0L
    private var txSequence = 1
    private var active: Work? = null

    fun upload(jpeg: ByteArray, complete: (Boolean) -> Unit = {}) {
        val image = jpeg.clone()
        dispatch {
            if (image.size !in 32..FakePodCoverPackets.MAX_JPEG_BYTES ||
                !FakePodCoverPackets.isJpeg(image)) {
                complete(false)
                return@dispatch
            }
            active?.let { old ->
                transport.onTransferFinished(false, old.jpeg.size, old.packetCount,
                    (System.nanoTime() - old.startedNs) / 1_000_000L)
                old.complete(false)
            } ?: transport.onTransferActive(true)
            active = Work(++sequence, txSequence++ and 0xFF, image, complete)
            pump()
        }
    }

    fun cancel() {
        dispatch {
            ++sequence
            active?.let { old ->
                transport.onTransferFinished(false, old.jpeg.size, old.packetCount,
                    (System.nanoTime() - old.startedNs) / 1_000_000L)
                old.complete(false)
                transport.onTransferActive(false)
            }
            active = null
        }
    }

    private fun pump() {
        val current = active ?: return
        val packet = when (current.phase) {
            Phase.BEGIN -> FakePodCoverPackets.begin(current.tx, current.jpeg)
            Phase.DATA -> FakePodCoverPackets.data(
                current.tx, current.offset, current.jpeg,
                transport.maxPacketBytes.coerceIn(20, FakePodCoverPackets.MAX_GATT_PACKET_BYTES))
            Phase.FINISH -> FakePodCoverPackets.finish(current.tx)
        }
        val serial = current.serial
        transport.writeCoverPacket(packet) { accepted ->
            dispatch {
                val work = active
                if (work == null || work.serial != serial) return@dispatch
                if (!accepted) {
                    active = null
                    transport.onTransferFinished(false, work.jpeg.size, work.packetCount,
                        (System.nanoTime() - work.startedNs) / 1_000_000L)
                    transport.onTransferActive(false)
                    work.complete(false)
                    return@dispatch
                }
                ++work.packetCount
                when (work.phase) {
                    Phase.BEGIN -> work.phase = Phase.DATA
                    Phase.DATA -> {
                        work.offset += packet.size - 6
                        if (work.offset == work.jpeg.size) work.phase = Phase.FINISH
                    }
                    Phase.FINISH -> {
                        active = null
                        transport.onTransferFinished(true, work.jpeg.size, work.packetCount,
                            (System.nanoTime() - work.startedNs) / 1_000_000L)
                        transport.onTransferActive(false)
                        work.complete(true)
                        return@dispatch
                    }
                }
                pump()
            }
        }
    }
}
