package com.fakepod.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** V0.5.6: one-time, device-screen authenticated NAS configuration transfer over Wi-Fi.
 * Never send credentials over Wi-Fi Remote V1 (TCP 46321).
 * The physical device button exposes TCP 46322 for 180 seconds and displays a 6-digit PIN.
 * PIN-derived encryption is only intended for a trusted Wi-Fi LAN (not 128-bit pairing strength).
 */
internal object NasWifiProvisioningClient {
    private const val PORT = 46322
    private val random = SecureRandom()
    private const val KDF_ROUNDS = 10000

    private fun readExactly(input: java.io.InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var count = 0
        while (count < length) {
            val read = input.read(out, count, length - count)
            if (read < 0) throw EOFException("FakePod 提前断开了 NAS 配置连接")
            count += read
        }
        return out
    }

    fun send(context: Context, deviceIp: String, displayedCode: String, musicUrl: String,
             username: String, password: String) {
        val code = displayedCode.filterNot { it.isWhitespace() || it == '-' }
        require(Regex("[0-9]{6}").matches(code)) { "请输入设备屏幕显示的 6 位数字授权码" }
        require(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}").matches(deviceIp)) {
            "请先通过 Wi-Fi 连接 FakePod"
        }
        val cleanUrl = musicUrl.trimEnd('/')
        require(cleanUrl.startsWith("http://") && cleanUrl.toByteArray(Charsets.UTF_8).size < 210 &&
            !cleanUrl.contains('\n') && !cleanUrl.contains('\r') &&
            !cleanUrl.contains('=') && !cleanUrl.contains(' ') &&
            !cleanUrl.contains('?') && !cleanUrl.contains('#') &&
            !cleanUrl.contains('@') && !cleanUrl.contains("..")) { "NAS 音乐目录 URL 不符合要求" }
        require(username.isNotEmpty() && username.toByteArray(Charsets.UTF_8).size < 64 &&
            username.none { it == '\n' || it == '\r' }) { "用户名过长或包含换行" }
        require(password.isNotEmpty() && password.toByteArray(Charsets.UTF_8).size < 96 &&
            password.none { it == '\n' || it == '\r' }) { "密码过长或包含换行" }
        val plain = "$cleanUrl\n$username\n$password".toByteArray(Charsets.UTF_8)
        require(plain.size in 1..700) { "NAS 配置太长" }
        try {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val wifi = manager?.allNetworks?.firstOrNull {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: throw IllegalStateException("手机没有可用的 Wi-Fi 网络")
            Socket().use { socket ->
                wifi.bindSocket(socket)
                socket.soTimeout = 7500
                socket.connect(InetSocketAddress(deviceIp, PORT), 3000)
                val input = socket.getInputStream()
                val challenge = readExactly(input, 20)
                if (String(challenge, 0, 4, Charsets.US_ASCII) != "FPC2")
                    throw IllegalStateException("设备协议不兼容：请同步升级 ESP32 至 FPN2 六位授权码版")
                val pinChars = code.toCharArray()
                val kdfSpec = PBEKeySpec(pinChars, challenge.copyOfRange(4, 20), KDF_ROUNDS, 128)
                val secret = try {
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(kdfSpec).encoded
                } finally {
                    pinChars.fill('0')
                    kdfSpec.clearPassword()
                }
                try {
                    val iv = ByteArray(12).also { random.nextBytes(it) }
                    val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, iv))
                        updateAAD("FakePod-NAS-Config-V2".toByteArray(Charsets.US_ASCII))
                        doFinal(plain)
                    }
                    try {
                        val cipherLength = encrypted.size - 16
                        socket.getOutputStream().apply {
                            write(byteArrayOf('F'.code.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), '2'.code.toByte(),
                                (cipherLength ushr 8).toByte(), cipherLength.toByte()))
                            write(iv)
                            write(encrypted)
                            flush()
                        }
                        val ack = readExactly(input, 33)
                        if (String(ack, 0, 4, Charsets.US_ASCII) != "FPA2")
                            throw IllegalStateException("设备没有返回有效的六位配对确认")
                        val response = Cipher.getInstance("AES/GCM/NoPadding").run {
                            init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"),
                                GCMParameterSpec(128, ack.copyOfRange(4, 16)))
                            updateAAD("FakePod-NAS-ACK-V2".toByteArray(Charsets.US_ASCII))
                            doFinal(ack.copyOfRange(16, 33))
                        }
                        if (response.size != 1 || response[0] != 0.toByte())
                            throw IllegalStateException("FakePod 拒绝了配置：请检查目录和凭据")
                    } finally {
                        encrypted.fill(0)
                    }
                } finally {
                    secret.fill(0)
                }
            }
        } finally {
            plain.fill(0)
        }
    }
}
