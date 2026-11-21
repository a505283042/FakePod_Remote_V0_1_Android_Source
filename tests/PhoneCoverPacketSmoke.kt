import com.fakepod.remote.cover.FakePodCoverPackets
import com.fakepod.remote.cover.FakePodCoverTransport
import com.fakepod.remote.cover.FakePodCoverUploader
import java.util.ArrayDeque

/**
 * Standalone JVM protocol smoke test (no Android classes).
 * kotlinc app/src/main/java/com/fakepod/remote/cover/FakePodCoverPackets.kt
 *         app/src/main/java/com/fakepod/remote/cover/FakePodCoverUploader.kt
 *         tests/PhoneCoverPacketSmoke.kt -include-runtime -d cover-smoke.jar
 * java -jar cover-smoke.jar
 */
fun main() {
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
        ByteArray(31_000) { (it % 171).toByte() } +
        byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    for (mtu in listOf(23, 100, 247)) {
        val frames = ArrayList<ByteArray>()
        val queue = ArrayDeque<() -> Unit>()
        val transport = object : FakePodCoverTransport {
            override val maxPacketBytes = FakePodCoverPackets.packetLimit(mtu)
            override fun writeCoverPacket(packet: ByteArray, done: (Boolean) -> Unit) {
                frames.add(packet)
                queue.addLast { done(true) }
            }
        }
        var ok = false
        val uploader = FakePodCoverUploader(transport) { runnable -> queue.addLast(runnable) }
        uploader.upload(jpeg) { ok = it }
        while (queue.isNotEmpty()) queue.removeFirst().invoke()
        check(ok)
        check(frames.first()[0] == 0x40.toByte())
        check(frames.last()[0] == 0x42.toByte())
        check(frames.all { it.size <= transport.maxPacketBytes })
        val recovered = ArrayList<Byte>()
        var expected = 0
        for (frame in frames.filter { it[0] == 0x41.toByte() }) {
            val off = (frame[2].toInt() and 255) or
                ((frame[3].toInt() and 255) shl 8) or
                ((frame[4].toInt() and 255) shl 16) or
                ((frame[5].toInt() and 255) shl 24)
            check(off == expected)
            recovered.addAll(frame.drop(6))
            expected += frame.size - 6
        }
        check(expected == jpeg.size)
        check(recovered.toByteArray().contentEquals(jpeg))
        println("MTU=$mtu frames=" + frames.size + " PASS")
    }
}
