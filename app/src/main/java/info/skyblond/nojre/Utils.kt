package info.skyblond.nojre

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

fun sha256ToKey(message: ByteArray): SecretKey {
    val md = MessageDigest.getInstance("SHA-256")
    val digest: ByteArray = md.digest(message)
    return SecretKeySpec(digest, "AES")
}

/**
 * Encrypt [plaintext] using [key]. Return cipher text and iv (nonce, 12 bytes, random generated).
 * */
fun encryptMessage(key: SecretKey, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    check(cipher.parameters.getParameterSpec(GCMParameterSpec::class.java).tLen == 128) { "HMAC size is not 128" }
    val ciphertext: ByteArray = cipher.doFinal(plaintext)
    val iv: ByteArray = cipher.iv
    return ciphertext to iv
}

fun decryptMessage(
    key: SecretKey, iv: ByteArray, ciphertext: ByteArray
): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
    return cipher.doFinal(ciphertext)
}