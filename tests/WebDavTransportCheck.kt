package com.fakepod.remote

/** JVM fixture check, paired with webdav_fixture.py; no Android device required. */
fun main(args: Array<String>) {
    val origin = args[0]
    val reader = NasCatalogWebDav(origin, "/share/Music", "/volume1/Music", "reader", "pass", { false })
    reader.use {
        val listing = org.json.JSONArray(it.list("/volume1/Music"))
        check(listing.length() == 2)
        val names = (0 until listing.length()).map { n -> listing.getJSONObject(n).getString("name") }
        check("中文 + #%曲目.mp3" in names)
        val stat = org.json.JSONObject(it.stat("/volume1/Music/中文 + #%曲目.mp3"))
        check(stat.getLong("size") == 100000L && !stat.getBoolean("directory"))
        check(it.read("/volume1/Music/中文 + #%曲目.mp3", 65000, 65536).size == 35000)
        check(it.read("/volume1/Music/中文 + #%曲目.mp3", 0, 100).contentEquals(ByteArray(100) { n -> n.toByte() }))
        check(it.getSmall("/volume1/Music/probe", 128).decodeToString() == "probe-ok")
        check(it.getSmall("/volume1/Music/flaky-probe", 128).decodeToString() == "probe-ok")
        val broken = runCatching { it.getSmall("/volume1/Music/broken-probe", 128) }.exceptionOrNull()
        check(broken is java.io.IOException && broken.message.orEmpty().contains("目录映射验证"))
        check(runCatching { it.read("/volume1/Music/ignore.mp3", 100, 10) }.isFailure)
        check(it.read("/volume1/Music/ignore.mp3", 0, 10).size == 10)
        check(runCatching { it.read("/volume1/Music/bad-range.mp3", 100, 10) }.isFailure)
        check(runCatching { it.list("/volume1/Music/outside") }.isFailure)
        check(runCatching { it.getSmall("/volume1/Music/probe", 2) }.isFailure)
    }
    println("WebDAV transport: listing, Unicode paths, stat, range boundaries, bounded reads and invalid responses PASS")
}
