package com.community.api.v1

import com.community.api.core.ApiException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Secrets {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val key: ByteArray by lazy {
        val configured = System.getenv("API_ENCRYPTION_KEY")
        if (configured != null) decoder.decode(configured).also { require(it.size == 32) { "API_ENCRYPTION_KEY must encode 32 bytes" } }
        else {
            require(System.getenv("APP_ENV") != "production") { "Set API_ENCRYPTION_KEY in production" }
            MessageDigest.getInstance("SHA-256").digest((System.getenv("DATABASE_PASSWORD") ?: "community-local-tests").toByteArray())
        }
    }
    fun token(bytes: Int = 32): String = encoder.encodeToString(ByteArray(bytes).also(random::nextBytes))
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun sign(value: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); encoder.encodeToString(doFinal(value.toByteArray()))
    }
    fun verifies(value: String, signature: String): Boolean = MessageDigest.isEqual(sign(value).toByteArray(), signature.toByteArray())
    fun seal(value: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return encoder.encodeToString(iv + cipher.doFinal(value.toByteArray()))
    }
    fun unseal(value: String): String = try {
        val bytes = decoder.decode(value)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    } catch (_: Exception) { throw ApiException(400, "VALIDATION_ERROR", "Invalid protected value") }
}
