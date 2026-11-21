package com.fakepod.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local credentials. Keystore keys and no-backup ciphertext never enter source or preferences. */
internal class NasCredentialStore(context: Context) {
    data class Login(val username: String, val password: String)
    private val directory = File(context.applicationContext.noBackupFilesDir, "nas-logins")

    fun load(protocol: String, host: String, port: Int): Login? = synchronized(LOCK) {
        try {
            val scope = scope(protocol, host, port)
            val file = AtomicFile(file(scope))
            if (!file.baseFile.exists()) return@synchronized null
            require(file.baseFile.length() <= 131072)
            val key = key(false) ?: return@synchronized null
            val json = JSONObject(String(NasCredentialCipher.decrypt(key, scope, file.readFully()), Charsets.UTF_8))
            Login(json.getString("username"), json.getString("password"))
        } catch (_: Exception) { null } // Missing/invalidated key: allow re-entry; never return corrupted data.
    }

    fun save(protocol: String, host: String, port: Int, username: String, password: String) = synchronized(LOCK) {
        require(username.isNotBlank() && password.isNotEmpty())
        if (load(protocol, host, port) == Login(username, password)) return@synchronized
        val scope = scope(protocol, host, port)
        val plain = JSONObject().put("username", username).put("password", password).toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= 65536) { "登录信息过长" }
        try {
            val encrypted = NasCredentialCipher.encrypt(requireNotNull(key(true)), scope, plain)
            check(directory.isDirectory || directory.mkdirs()) { "无法创建登录信息目录" }
            val file = AtomicFile(file(scope))
            val output = file.startWrite()
            try { output.write(encrypted); file.finishWrite(output) }
            catch (e: Exception) { file.failWrite(output); throw e }
        } catch (e: Exception) { throw IllegalStateException("无法保存登录信息，请重试", e) }
        finally { plain.fill(0) }
    }

    private fun file(scope: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "$hash.bin")
    }
    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    companion object {
        private val LOCK = Any()
        private const val ALIAS = "fakepod.nas.credentials.v1"
        internal fun scope(protocol: String, host: String, port: Int): String {
            require(protocol in setOf("webdav", "smb", "ssh") && host.isNotBlank() && port in 1..65535)
            return "$protocol\n${host.trim().lowercase(java.util.Locale.ROOT)}\n$port"
        }
    }
}

/** Authenticated envelope, shared by Android Keystore and JVM cryptographic regression checks. */
internal object NasCredentialCipher {
    fun encrypt(key: SecretKey, scope: String, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(scope.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == 12)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
    }
    fun decrypt(key: SecretKey, scope: String, envelope: ByteArray): ByteArray {
        require(envelope.size in 29..131072 && envelope[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
        cipher.updateAAD(scope.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(envelope.copyOfRange(13, envelope.size))
    }
}
