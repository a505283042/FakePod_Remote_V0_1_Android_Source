package com.fakepod.remote

import okhttp3.ConnectionPool
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Bounded WebDAV directory/stat/range reader for the unchanged phone Python scanner. */
internal class NasCatalogWebDav(
    origin: String,
    davPath: String,
    private val physicalRoot: String,
    username: String,
    password: String,
    private val cancelled: () -> Boolean
) : AutoCloseable {
    private val rootUrl = (origin + davPath.trimEnd('/') + "/").toHttpUrl()
    private val authorization = Credentials.basic(username, password, StandardCharsets.UTF_8)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .retryOnConnectionFailure(true).build()

    private fun check() {
        check(!cancelled() && !Thread.currentThread().isInterrupted) { "扫描已取消" }
    }

    private fun url(path: String, directory: Boolean = false): HttpUrl {
        require(path == physicalRoot || path.startsWith(physicalRoot + "/")) { "目录超出所选音乐范围" }
        val relative = path.removePrefix(physicalRoot).trimStart('/')
        require(relative.split('/').none { it == "." || it == ".." })
        return rootUrl.newBuilder().apply {
            if (relative.isNotEmpty()) addPathSegments(relative)
            if (directory && relative.isNotEmpty()) addPathSegment("")
        }.build()
    }

    private fun limited(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (out.size() <= limit) {
            check()
            val n = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        require(out.size() <= limit) { "WebDAV 响应超过读取上限" }
        return out.toByteArray()
    }

    private data class Entry(val path: String, val directory: Boolean, val size: Long, val mtime: Long) {
        fun json() = JSONObject().put("name", path.substringAfterLast('/')).put("directory", directory)
            .put("size", size).put("mtime", mtime)
    }

    private fun properties(path: String, depth: String): List<Entry> {
        check()
        val target = url(path, depth == "1")
        // Match the outer folder browser's bodyless PROPFIND for older WebDAV servers.
        val request = Request.Builder().url(target).header("Authorization", authorization)
            .header("Connection", "close").header("Accept-Encoding", "identity")
            .header("Depth", depth).method("PROPFIND", null).build()
        val data = withResponse(request, "目录属性读取：${path.substringAfterLast('/')}") { response ->
            require(response.code == 207) { "WebDAV 目录读取失败：HTTP ${response.code}" }
            val body = response.body ?: error("WebDAV 返回空目录响应")
            body.byteStream().use { limited(it, 16 * 1024 * 1024) }
        }
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(ByteArrayInputStream(data), "UTF-8")
        val entries = ArrayList<Entry>()
        var href = ""
        var folder = false
        var size: Long? = null
        var modified: Long? = null
        var propFolder = false
        var propSize: Long? = null
        var propModified: Long? = null
        var status = ""
        var inResponse = false
        var inPropstat = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.namespace == "DAV:") {
                when (parser.name) {
                    "response" -> { inResponse = true; href = ""; folder = false; size = null; modified = null }
                    "href" -> if (inResponse && !inPropstat) href = parser.nextText().trim()
                    "propstat" -> { inPropstat = true; propFolder = false; propSize = null; propModified = null; status = "" }
                    "status" -> if (inPropstat) status = parser.nextText()
                    "collection" -> if (inPropstat) propFolder = true
                    "getcontentlength" -> if (inPropstat) propSize = parser.nextText().trim().toLongOrNull()
                    "getlastmodified" -> if (inPropstat) {
                        val value = parser.nextText().trim()
                        propModified = try { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }
                            catch (_: Exception) { null }
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.namespace == "DAV:") {
                when (parser.name) {
                    "propstat" -> {
                        if (Regex("^HTTP/[^ ]+ 200(?: |$)").containsMatchIn(status.trim())) {
                            folder = folder || propFolder
                            if (propSize != null) size = propSize
                            if (propModified != null) modified = propModified
                        }
                        inPropstat = false
                    }
                    "response" -> {
                        if (href.isNotEmpty()) {
                            val resolved = target.toUri().resolve(URI(href)).normalize()
                            require(resolved.scheme.equals(target.scheme, true) && resolved.host.equals(target.host, true) &&
                                (if (resolved.port == -1) 80 else resolved.port) == target.port &&
                                resolved.rawQuery == null && resolved.rawFragment == null) { "WebDAV 返回了其他服务器的路径" }
                            val decoded = resolved.path.trimEnd('/')
                            val requested = target.toUri().path.trimEnd('/')
                            require(decoded == requested || decoded.startsWith(requested + "/")) { "WebDAV 路径超出请求目录" }
                            if (!folder) require(size != null && size >= 0 && modified != null) { "WebDAV 缺少文件大小或修改时间，无法安全增量扫描" }
                            entries.add(Entry(decoded, folder, size ?: 0, modified ?: 0))
                        }
                        inResponse = false
                    }
                }
            }
            event = parser.next()
        }
        return entries
    }

    fun list(path: String): String {
        val requested = url(path, true).toUri().path.trimEnd('/')
        val rows = JSONArray()
        val seen = HashSet<String>()
        for (entry in properties(path, "1")) {
            if (entry.path == requested) continue
            val name = entry.path.removePrefix(requested + "/")
            require('/' !in name && name.isNotEmpty() && name != "." && name != "..") { "WebDAV 返回了非直接子目录" }
            require(seen.add(name)) { "WebDAV 返回了重复文件名" }
            rows.put(entry.json())
        }
        return rows.toString()
    }

    fun stat(path: String): String {
        val expected = url(path).toUri().path.trimEnd('/')
        return properties(path, "0").singleOrNull { it.path == expected }?.json()?.toString()
            ?: error("WebDAV 未返回目标文件属性")
    }

    fun read(path: String, offset: Long, count: Int): ByteArray {
        check()
        require(offset >= 0 && count in 0..131072)
        if (count == 0) return byteArrayOf()
        val request = Request.Builder().url(url(path)).header("Authorization", authorization)
            .header("Connection", "close").header("Accept-Encoding", "identity").header("Range", "bytes=$offset-${offset + count - 1}").build()
        return withResponse(request, "歌曲分段读取：${path.substringAfterLast('/')}，偏移 $offset") { response ->
            require(response.code == 206 || (response.code == 200 && offset == 0L)) {
                "WebDAV 不支持所需的分段读取：HTTP ${response.code}"
            }
            require(response.header("Content-Encoding").let { it == null || it.equals("identity", true) }) { "WebDAV 音频响应被压缩，无法定位" }
            var expectedBytes: Int? = null
            if (response.code == 206) {
                val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range").orEmpty())
                    ?: error("WebDAV 分段响应缺少有效 Content-Range")
                val end = range.groupValues[2].toLong()
                val total = range.groupValues[3].toLong()
                require(range.groupValues[1].toLong() == offset && end >= offset && total > end &&
                    end == minOf(total - 1, offset + count - 1)) { "WebDAV 返回的分段偏移不正确" }
                expectedBytes = (end - offset + 1).toInt()
            }
            val body = response.body ?: error("WebDAV 返回空音频数据")
            // Servers ignoring a range at offset zero are tolerated without downloading the full song.
            body.byteStream().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                while (out.size() < count) {
                    check()
                    val n = input.read(buffer, 0, minOf(buffer.size, count - out.size()))
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                val bytes = out.toByteArray()
                require(expectedBytes == null || bytes.size == expectedBytes) { "WebDAV 分段数据不完整" }
                bytes
            }
        }
    }

    fun getSmall(path: String, limit: Int): ByteArray {
        val request = Request.Builder().url(url(path)).header("Authorization", authorization)
            .header("Connection", "close").header("Accept-Encoding", "identity").build()
        return withResponse(request, "SSH/WebDAV 目录映射验证") { response ->
            require(response.code == 200) { "WebDAV 无法读取 SSH 目录：HTTP ${response.code}" }
            val body = response.body ?: error("WebDAV 返回空内容")
            body.byteStream().use { limited(it, limit) }
        }
    }

    /** Retry only read operations, including failures while consuming a truncated response. */
    private fun <T> withResponse(request: Request, stage: String, read: (Response) -> T): T {
        for (attempt in 0..1) {
            check()
            try {
                return client.newCall(request).execute().use(read)
            } catch (e: IOException) {
                client.connectionPool.evictAll()
                if (attempt == 1 || cancelled() || Thread.currentThread().isInterrupted)
                    throw IOException("$stage 失败（已使用独立连接重试）：${e.message}", e)
            }
        }
        error("unreachable")
    }

    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
}
