package com.fakepod.remote

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** SMB 2.0.2 file transport, matching the dialect observed on the user's NAS. */
internal class NasCatalogSmb(
    private val host: String,
    private val physicalRoot: String,
    smbPath: String,
    private val username: String,
    password: String,
    private val cancelled: () -> Boolean,
    private val port: Int = 445,
    private val report: (String) -> Unit = {}
) : AutoCloseable {
    private val secret = password.toCharArray()
    private val parts = parseRoot(smbPath)
    private val client = SMBClient(SmbConfig.builder().withDialects(SMB2Dialect.SMB_2_0_2)
        .withTimeout(30, TimeUnit.SECONDS).withSoTimeout(30, TimeUnit.SECONDS).build())
    private var connection: Connection? = null
    private var session: Session? = null
    private lateinit var share: DiskShare

    private var readPath: String? = null
    private var readFile: com.hierynomus.smbj.share.File? = null
    private var readLength = 0L

    init {
        try { connect() }
        catch (e: Exception) { close(); throw IllegalStateException("SMB 2.0.2 连接失败：${e.message}", e) }
    }

    private fun connect() {
        check()
        val conn = client.connect(host, port)
        connection = conn
        val domain = if ('\\' in username) username.substringBefore('\\') else ""
        val account = username.substringAfter('\\', username)
        val auth = conn.authenticate(AuthenticationContext(account, secret, domain))
        session = auth
        val resource = auth.connectShare(parts.first)
        require(resource is DiskShare) { "所选 SMB 共享不是文件共享" }
        share = resource
        require(exists(parts.second, true)) { "SMB 音乐目录不存在，请检查共享名称和路径" }
    }

    private fun disconnect() {
        try { readFile?.close() } catch (_: Exception) { }
        readFile = null; readPath = null
        try { if (::share.isInitialized) share.close() } catch (_: Exception) { }
        try { session?.close() } catch (_: Exception) { }
        try { connection?.close() } catch (_: Exception) { }
        session = null; connection = null
    }

    /** Retry only read-only operations on transport loss, never SMB permission/path errors or writes. */
    private fun <T> reading(action: () -> T): T {
        var last: Exception? = null
        for (attempt in 0..2) {
            check()
            try {
                if (connection?.isConnected == false) disconnect()
                if (connection == null) connect()
                return action()
            } catch (e: Exception) {
                check()
                val causes = generateSequence<Throwable>(e) { it.cause }.take(16).toList()
                if (causes.any { it is SMBApiException } || causes.none {
                    it is java.io.IOException || it.javaClass.name.endsWith("TransportException")
                }) throw e
                last = e
                disconnect()
                if (attempt < 2) {
                    report("SMB 连接中断，正在重连 ${attempt + 1}/2…")
                    // Short cancellable backoff, without retrying publication commands.
                    repeat(10 * (attempt + 1)) { check(); Thread.sleep(100) }
                }
            }
        }
        throw IllegalStateException("SMB 重连失败，已保留扫描检查点，请检查 Wi-Fi 后重扫", last)
    }

    private fun exists(path: String, directory: Boolean): Boolean = try {
        if (directory) share.folderExists(path) else share.fileExists(path)
    } catch (e: SMBApiException) {
        // Some older servers return NO_SUCH_FILE rather than OBJECT_NAME_NOT_FOUND.
        if (e.statusCode in setOf(0xc000000fL, 0xc0000034L, 0xc000003aL)) false else throw e
    }

    private fun check() {
        check(!cancelled() && !Thread.currentThread().isInterrupted) { "扫描已取消" }
    }

    private fun path(physical: String): String = relative(parts.second, physicalRoot, physical)

    private fun info(name: String, attributes: Long, size: Long, millis: Long): JSONObject {
        require(attributes and 0x400L == 0L) { "音乐目录包含重解析点或符号链接：$name" }
        require(size >= 0 && millis >= 0) { "SMB 文件大小或修改时间无效" }
        return JSONObject().put("name", name).put("directory", attributes and 0x10L != 0L)
            .put("size", size).put("mtime", millis / 1000)
    }

    fun list(physical: String): String = reading {
        check()
        val rows = JSONArray()
        val names = HashSet<String>()
        for (entry in share.list(path(physical))) {
            check()
            val name = entry.fileName
            if (name == "." || name == "..") continue
            require('/' !in name && '\\' !in name && '\u0000' !in name && names.add(name)) { "SMB 返回了无效或重复文件名" }
            rows.put(info(name, entry.fileAttributes, entry.endOfFile, entry.lastWriteTime.toEpochMillis()))
            require(rows.length() <= 100000) { "单目录超过10万个文件" }
        }
        rows.toString()
    }

    fun stat(physical: String): String = reading {
        check()
        val entry = share.getFileInformation(path(physical))
        info(physical.substringAfterLast('/'), entry.basicInformation.fileAttributes,
            entry.standardInformation.endOfFile, entry.basicInformation.lastWriteTime.toEpochMillis()).toString()
    }

    private fun open(physical: String, write: Boolean = false): com.hierynomus.smbj.share.File {
        return share.openFile(path(physical),
            EnumSet.of(if (write) AccessMask.GENERIC_WRITE else AccessMask.GENERIC_READ),
            EnumSet.noneOf(FileAttributes::class.java), EnumSet.allOf(SMB2ShareAccess::class.java),
            if (write) SMB2CreateDisposition.FILE_OVERWRITE_IF else SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
    }

    fun read(physical: String, offset: Long, count: Int): ByteArray = reading {
        check()
        require(offset >= 0 && count in 0..131072)
        if (count == 0) return@reading byteArrayOf()
        if (readPath != physical) {
            readFile?.close(); readFile = null; readPath = null
            val file = open(physical)
            readFile = file
            readLength = file.getFileInformation(com.hierynomus.msfscc.fileinformation.FileStandardInformation::class.java).endOfFile
            readPath = physical
        }
        val file = requireNotNull(readFile)
        run {
            val length = readLength
            val wanted = minOf(count.toLong(), (length - offset).coerceAtLeast(0)).toInt()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(minOf(count, 65536))
            while (out.size() < wanted) {
                check()
                val n = file.read(buffer, offset + out.size(), 0, minOf(buffer.size, wanted - out.size()))
                if (n < 0) break
                require(n > 0) { "SMB 文件读取没有进展" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }

    fun readSmall(physical: String, limit: Int, missingAllowed: Boolean = false): ByteArray = reading {
        check()
        require(limit in 0..33554432)
        if (missingAllowed && !exists(path(physical), false)) return@reading byteArrayOf()
        open(physical).use { file ->
            val size = file.getFileInformation(com.hierynomus.msfscc.fileinformation.FileStandardInformation::class.java).endOfFile
            require(size in 0..limit.toLong()) { "SMB 文件超过读取上限" }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (out.size().toLong() < size) {
                check()
                val n = file.read(buffer, out.size().toLong(), 0, minOf(buffer.size.toLong(), size - out.size()).toInt())
                require(n > 0) { "SMB 文件提前结束" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }

    fun mkdir(physical: String) {
        check()
        var current = ""
        for (part in path(physical).split('\\').filter { it.isNotEmpty() }) {
            current = if (current.isEmpty()) part else "$current\\$part"
            if (!exists(current, true)) share.mkdir(current)
        }
    }

    fun upload(local: File, physical: String) {
        check()
        require(local.length() <= 33554432) { "索引文件过大" }
        open(physical, true).use { file ->
            file.outputStream.use { output ->
                local.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        check()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                }
            }
        }
        val expected = MessageDigest.getInstance("SHA-256").digest(local.readBytes())
        val actual = MessageDigest.getInstance("SHA-256").digest(readSmall(physical, local.length().toInt()))
        require(actual.contentEquals(expected)) { "SMB 上传回读校验失败，未发布索引" }
    }

    override fun close() {
        disconnect()
        try { client.close() } catch (_: Exception) { }
        secret.fill('\u0000')
    }

    companion object {
        fun parseRoot(path: String): Pair<String, String> {
            val parts = path.trim('/').split('/')
            require(path.startsWith('/') && parts.isNotEmpty() && parts.all {
                it.isNotBlank() && it != "." && it != ".." && '\\' !in it && '\u0000' !in it
            }) { "SMB 路径应为 /共享名/音乐目录，不填写服务器地址" }
            return parts.first() to parts.drop(1).joinToString("\\")
        }

        fun relative(prefix: String, root: String, physical: String): String {
            require(physical == root || physical.startsWith(root + "/")) { "SMB 路径超出音乐目录" }
            val tail = physical.removePrefix(root).trimStart('/')
            require(tail.split('/').none { it == "." || it == ".." || '\\' in it || '\u0000' in it }) { "SMB 文件路径无效" }
            return listOf(prefix, tail.replace('/', '\\')).filter { it.isNotEmpty() }.joinToString("\\")
        }
    }
}
