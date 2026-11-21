package com.fakepod.remote

import android.content.Context
import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** SMB 2.0.2 file transport plus ordinary SSH hard-link publication. */
class NasSshBridge(
    context: Context,
    host: String,
    port: Int,
    username: String,
    password: String,
    legacy: Boolean,
    private val root: String,
    private val davOrigin: String,
    private val davPath: String,
    davUser: String,
    davPassword: String,
    smbPath: String,
    smbUser: String,
    smbPassword: String,
    confirmHost: (String) -> Boolean,
    private val onProgress: (String) -> Unit,
    private val cancelled: () -> Boolean
) : AutoCloseable {
    private val session: Session
    private val dav = NasCatalogWebDav(davOrigin, davPath, root, davUser, davPassword, cancelled)
    private val smb = NasCatalogSmb(host, root, smbPath, smbUser, smbPassword, cancelled, report = onProgress)
    private var committing = false

    init {
        val pins = context.getSharedPreferences("fakepod_ssh_hosts", Context.MODE_PRIVATE)
        val jsch = JSch()
        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(name: String, key: ByteArray): Int {
                val value = Base64.encodeToString(key, Base64.NO_WRAP)
                val known = pins.getString(name, null)
                if (known != null) return if (known == value) HostKeyRepository.OK else HostKeyRepository.CHANGED
                val fingerprint = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key), Base64.NO_WRAP).trimEnd('=')
                if (!confirmHost("$name\nSHA256:$fingerprint")) return HostKeyRepository.CHANGED
                // Pin before authentication; a failed password must never silently replace a host identity.
                if (!pins.edit().putString(name, value).commit()) throw IllegalStateException("无法保存 NAS 主机指纹")
                return HostKeyRepository.OK
            }
            override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
            override fun remove(host: String?, type: String?) = Unit
            override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
            override fun getKnownHostsRepositoryID(): String = "FakePod pinned NAS hosts"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        session = jsch.getSession(username, host, port).apply {
            setPassword(password)
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", "password,keyboard-interactive")
            // Explicit per-connection option for older DSM. No insecure global defaults.
            if (legacy) {
                setConfig("server_host_key", getConfig("server_host_key") + ",ssh-rsa")
                setConfig("kex", getConfig("kex") + ",diffie-hellman-group14-sha1,diffie-hellman-group1-sha1")
            }
            timeout = 30000
            setServerAliveInterval(15000)
        }
        try {
            session.connect(20000)
            onProgress("SSH 已登录，正在检查普通命令通道…")
            val actualRoot = String(execBytes("cd " + quote(root) + " && pwd -P", null, 16384), StandardCharsets.UTF_8).trimEnd('\r', '\n')
            require(actualRoot == root.trimEnd('/')) {
                "音乐目录必须填写 NAS 真实路径，不要使用符号链接"
            }
        } catch (e: Exception) {
            session.disconnect()
            smb.close()
            dav.close()
            throw e
        }
    }

    fun checkpoint() {
        if (!committing && (cancelled() || Thread.currentThread().isInterrupted))
            throw IllegalStateException("扫描已取消；未发布新索引")
    }

    fun progress(message: String) { checkpoint(); onProgress(message) }

    private fun safePath(path: String): String {
        require(path == root || path.startsWith(root.trimEnd('/') + "/")) { "路径超出音乐目录" }
        require(path.split('/').none { it == ".." || it == "." } && '\u0000' !in path)
        return path
    }

    fun list(path: String): String { checkpoint(); return smb.list(safePath(path)) }

    fun stat(path: String): String { checkpoint(); return smb.stat(safePath(path)) }

    fun read(path: String, offset: Long, count: Int): String {
        checkpoint()
        return Base64.encodeToString(smb.read(safePath(path), offset, count), Base64.NO_WRAP)
    }

    fun readSmall(path: String, limit: Int): String {
        checkpoint()
        require(limit in 1..33554432)
        return String(smb.readSmall(safePath(path), limit, true), StandardCharsets.UTF_8)
    }

    fun mkdir(path: String) {
        checkpoint()
        smb.mkdir(safePath(path))
    }

    /** Prove that the selected WebDAV folder maps to this SSH physical root. */
    fun verifyMapping() {
        val links = execBytes("find " + quote(root) + " -type l -print", null, 65536)
        require(links.isEmpty()) { "音乐目录内有符号链接，请使用普通文件和硬链接" }
        mkdir("$root/_FakePod/music-index")
        val path = "$root/_FakePod/music-index/fakepod-app-probe-${UUID.randomUUID()}"
        val data = UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8)
        try {
            execBytes("cat > " + quote(path), ByteArrayInputStream(data), 4096)
            val smbData = smb.readSmall(path, 128)
            require(smbData.contentEquals(data)) { "SMB 与 SSH 指向不同目录，请修改 SMB 路径或 SSH 路径" }
            val actual = dav.getSmall(path, 128)
            require(actual.contentEquals(data)) { "WebDAV 与 SSH 目录不一致，请检查真实音乐路径" }
        } finally { try { execBytes("rm -f " + quote(path), null, 4096) } catch (_: Exception) { } }
    }

    fun upload(localPath: String, remotePath: String) {
        checkpoint()
        smb.upload(File(localPath), safePath(remotePath))
    }

    private fun execBytes(command: String, input: java.io.InputStream?, limit: Int): ByteArray {
        val channel = session.openChannel("exec") as ChannelExec
        try {
            channel.setCommand(command)
            channel.setInputStream(input, true)
            val output = channel.inputStream
            val errors = java.io.ByteArrayOutputStream()
            channel.setErrStream(errors)
            channel.connect(15000)
            val bytes = output.use { it.readNBytesCompat(limit + 1) }
            require(bytes.size <= limit) { "NAS 返回文件超过读取上限" }
            while (!channel.isClosed) Thread.sleep(20)
            require(channel.exitStatus == 0) {
                "SSH 命令失败（${channel.exitStatus}）：" + String(errors.toByteArray(), StandardCharsets.UTF_8).takeLast(400)
            }
            return bytes
        } catch (e: Exception) {
            if (e.message?.contains("failed to send channel request") == true)
                throw IllegalStateException("NAS 拒绝普通 SSH 命令通道，请检查该账号的远程命令权限", e)
            throw e
        } finally { channel.disconnect() }
    }

    /** Once publication starts, do not interrupt its rollback/commit sequence. */
    fun publish(script: String, stage: String): String {
        checkpoint()
        committing = true
        onProgress("正在创建硬链接并发布索引，请保持 APP 和 Wi-Fi 开启…")
        val channel = session.openChannel("exec") as ChannelExec
        try {
            channel.setCommand("sh " + quote(script) + " " + quote(root) + " " + quote(stage))
            channel.setInputStream(null)
            val output = channel.inputStream
            val errors = java.io.ByteArrayOutputStream()
            channel.setErrStream(errors)
            channel.connect(15000)
            val log = output.use { it.readNBytesCompat(262144) }
            while (!channel.isClosed) Thread.sleep(50)
            require(channel.exitStatus == 0) {
                "NAS 发布失败（${channel.exitStatus}）；请检查目录内备份与恢复说明。" +
                    String(errors.toByteArray(), StandardCharsets.UTF_8).takeLast(500)
            }
            return String(log, StandardCharsets.UTF_8)
        } finally { channel.disconnect(); committing = false }
    }

    override fun close() { smb.close(); session.disconnect(); dav.close() }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
