package com.community.api.identity

import com.community.api.core.badRequest
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Passwords {
    private const val ITERATIONS = 600_000
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val dummy by lazy { hash("unused-constant-timing-password-41") }

    fun validate(password: String) {
        if (password.length !in 12..256) badRequest("Password must contain 12 to 256 characters")
    }

    fun hash(password: String): String {
        validate(password)
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2-sha256:$ITERATIONS:${encoder.encodeToString(salt)}:${encoder.encodeToString(derive(password, salt, ITERATIONS))}"
    }

    fun verify(password: String, encoded: String?): Boolean {
        if (password.length > 256) return false
        val value = encoded?.takeIf { it.isNotEmpty() } ?: dummy
        val matched = runCatching {
            val parts = value.split(':')
            require(parts.size == 4 && parts[0] == "pbkdf2-sha256")
            val iterations = parts[1].toInt().also { require(it in ITERATIONS..1_200_000) }
            MessageDigest.isEqual(decoder.decode(parts[3]), derive(password, decoder.decode(parts[2]), iterations))
        }.getOrDefault(false)
        return !encoded.isNullOrEmpty() && matched
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}

internal fun secretToken(): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

internal fun digest(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

internal fun sameSecret(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
