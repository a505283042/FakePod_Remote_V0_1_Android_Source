package com.fakepod.remote

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Integration fixture: actual SMB 2.0.2 server with a music share. */
fun main(args: Array<String>) {
    val cancel = AtomicBoolean(false)
    check(NasCatalogSmb.parseRoot("/麦田广告/Music") == ("麦田广告" to "Music"))
    check(NasCatalogSmb.relative("Music", "/volume1/麦田广告/Music", "/volume1/麦田广告/Music/中文 + #%.mp3") == "Music\\中文 + #%.mp3")
    check(runCatching { NasCatalogSmb.parseRoot("/share/../other") }.isFailure)
    check(runCatching { NasCatalogSmb.relative("", "/music", "/music-other/x") }.isFailure)
    val adapter = NasCatalogSmb("127.0.0.1", "/volume1/Music", "/music/中文目录", "reader", "pass", { cancel.get() }, args[0].toInt())
    adapter.use {
        val rows = JSONArray(it.list("/volume1/Music"))
        check(rows.length() == 2)
        check((0 until rows.length()).any { n -> rows.getJSONObject(n).getString("name") == "中文 + #%曲目.mp3" })
        val physical = "/volume1/Music/中文 + #%曲目.mp3"
        val stat = JSONObject(it.stat(physical))
        check(stat.getLong("size") == 100000L && stat.getLong("mtime") == 1700000000L)
        check(it.read(physical, 65000, 65536).contentEquals(ByteArray(35000) { n -> ((n + 65000) % 256).toByte() }))
        check(it.read(physical, 0, 65536).contentEquals(ByteArray(65536) { n -> (n % 256).toByte() }))
        // Sever a real SMB transport: the next read must reconnect and reopen its cached handle.
        val connectionField = NasCatalogSmb::class.java.getDeclaredField("connection").apply { isAccessible = true }
        (connectionField.get(it) as com.hierynomus.smbj.connection.Connection).close()
        check(it.read(physical, 65536, 65536).contentEquals(ByteArray(34464) { n -> ((n + 65536) % 256).toByte() }))
        check(it.readSmall("/volume1/Music/missing.json", 1000, true).isEmpty())
        check(runCatching { it.readSmall(physical, 100) }.isFailure)
        it.mkdir("/volume1/Music/_FakePod/stage")
        val local = File(args[1])
        val destination = "/volume1/Music/_FakePod/stage/index.bin"
        it.upload(local, destination)
        check(it.readSmall(destination, 2 * 1024 * 1024).contentEquals(local.readBytes()))
        cancel.set(true)
        check(runCatching { it.list("/volume1/Music") }.isFailure)
    }
    println("SMB 2.0.2 integration: Unicode listing, file metadata, random reads, bounded reads, mkdir, binary upload/readback and cancellation PASS")
}
