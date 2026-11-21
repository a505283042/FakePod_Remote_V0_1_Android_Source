package com.fakepod.remote

import javax.crypto.KeyGenerator

fun main() {
    val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val scope = NasCredentialStore.scope("ssh", "NAS.local", 33)
    check(scope == NasCredentialStore.scope("ssh", " nas.LOCAL ", 33))
    val plain = "{\"username\":\"用户名\",\"password\":\" 密码\\\"$! \\".toByteArray(Charsets.UTF_8)
    val first = NasCredentialCipher.encrypt(key, scope, plain)
    val second = NasCredentialCipher.encrypt(key, scope, plain)
    check(!first.contentEquals(second))
    check(NasCredentialCipher.decrypt(key, scope, first).contentEquals(plain))
    check(runCatching { NasCredentialCipher.decrypt(key, NasCredentialStore.scope("smb", "NAS.local", 445), first) }.isFailure)
    check(runCatching { NasCredentialCipher.decrypt(key, NasCredentialStore.scope("ssh", "other", 33), first) }.isFailure)
    check(runCatching { NasCredentialCipher.decrypt(key, NasCredentialStore.scope("ssh", "NAS.local", 22), first) }.isFailure)
    first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
    check(runCatching { NasCredentialCipher.decrypt(key, scope, first) }.isFailure)
    check(runCatching { NasCredentialCipher.decrypt(key, scope, byteArrayOf(1)) }.isFailure)
    println("Credentials: Unicode roundtrip, randomized IV, endpoint/protocol binding and tamper rejection PASS")
}
