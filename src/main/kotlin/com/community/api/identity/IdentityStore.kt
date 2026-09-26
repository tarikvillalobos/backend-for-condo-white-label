package com.community.api.identity

import com.community.api.core.*
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.Locale

internal data class AuthResult<T>(val value: T? = null, val status: Int = 401, val code: String = "invalid_credentials") {
    fun unwrap(): T = value ?: throw ApiException(status, code, if (status == 429) "Too many attempts; try again later" else "Invalid or expired credentials")
}

internal fun normalizedEmail(value: String): String {
    val email = value.trim().lowercase(Locale.ROOT)
    if (email.length !in 3..254 || !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email)) badRequest("Invalid email address")
    return email
}

internal fun normalizedName(value: String): String = value.trim().also {
    if (it.length !in 1..120) badRequest("Name must contain 1 to 120 characters")
