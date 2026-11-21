package com.fakepod.remote

/** Default ATT MTU=23: 20-byte values, ordered fragments, bounded assembly. */
internal class BleLibraryAssembler {
    private var bytes=ByteArray(0)
    private var received=0
    fun reset(){bytes=ByteArray(0);received=0}
    fun accept(packet: ByteArray): ByteArray? {
        require(packet.size in 4..20 && packet[0].toInt()==7)
        val offset=packet[1].toInt() and 255
        val total=packet[2].toInt() and 255
        require(total in 17..255 && offset+packet.size-3<=total)
        if(offset==0){bytes=ByteArray(total);received=0}
        require(bytes.size==total && offset==received)
        packet.copyInto(bytes,offset,3,packet.size);received+=packet.size-3
        if(received!=total)return null
        return bytes.also {reset()}
    }
    companion object {
        fun requestFragments(request: ByteArray): List<ByteArray> {
            require(request.size==32 && request[0].toInt()==0x30 && request[1].toInt()==1)
            return listOf(0,16).map {offset->byteArrayOf(0x31,offset.toByte())+request.copyOfRange(offset,offset+16)}
        }
    }
}
