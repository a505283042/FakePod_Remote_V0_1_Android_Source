package com.fakepod.remote.cover

/**
 * Uses the existing serialized encrypted GATT Command writer. Each completion
 * callback must be delivered by the matching onCharacteristicWrite event.
 */
interface FakePodCoverTransport {
    val maxPacketBytes: Int
    fun writeCoverPacket(packet: ByteArray, done: (Boolean) -> Unit)
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
        var phase: Phase = Phase.BEGIN
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
            active?.complete?.invoke(false)
            active = Work(++sequence, txSequence++ and 0xFF, image, complete)
            pump()
        }
    }

    fun cancel() {
        dispatch {
            ++sequence
            active?.complete?.invoke(false)
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
                    work.complete(false)
                    return@dispatch
                }
                when (work.phase) {
                    Phase.BEGIN -> work.phase = Phase.DATA
                    Phase.DATA -> {
                        work.offset += packet.size - 6
                        if (work.offset == work.jpeg.size) work.phase = Phase.FINISH
                    }
                    Phase.FINISH -> {
                        active = null
                        work.complete(true)
                        return@dispatch
                    }
                }
                pump()
            }
        }
    }
}
