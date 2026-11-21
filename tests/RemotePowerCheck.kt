package com.fakepod.remote

import java.io.InputStream
import java.net.SocketTimeoutException

fun main() {
    check((0..7).map(RemotePowerPolicy::retryDelay) == listOf(700L,1400L,2800L,5600L,11200L,15000L,15000L,15000L))
    check(RemotePowerPolicy.retryDelay(Int.MAX_VALUE)==15000L)
    check(RemotePowerPolicy.retryDelay(-1)==700L)
    // A slow/modem-sleep peer must not lose a partial frame across receive timeouts.
    val bytes = intArrayOf(0,0xA5,-2,3,1,-2,2,-2,3,0xA5,1,9,-1)
    val stream = object: InputStream() {
        var offset=0
        override fun read(): Int {
            val value=bytes[offset++]
            if(value == -2) throw SocketTimeoutException()
            return value
        }
        override fun read(b: ByteArray,off: Int,len: Int): Int {
            val value=read();if(value<0)return -1;b[off]=value.toByte();return 1
        }
    }
    val reader=WifiFrameReader()
    repeat(3){check(reader.poll(stream)?.isEmpty()==true)}
    check(reader.poll(stream)!!.contentEquals(byteArrayOf(1,2,3)))
    check(reader.poll(stream)!!.contentEquals(byteArrayOf(9)))
    check(reader.poll(stream)==null)
    println("PASS: bounded retry/reset delay and partial-frame retention across idle timeouts")
}
