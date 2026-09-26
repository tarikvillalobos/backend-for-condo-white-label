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
}

internal fun Tx.findAccount(tenantId: String, email: String): Record? =
    list("account", tenantId).firstOrNull { it.decode<Account>().email == email }

internal fun Tx.tenantAvailable(tenantId: String): Boolean {
    val tenant = get("client", tenantId, tenantId) ?: return false
    return tenant.data["active"]?.jsonPrimitive?.booleanOrNull != false
}

internal fun Tx.loginEnabled(tenantId: String, policy: String, default: Boolean): Boolean =
    get("client", tenantId, tenantId)?.data?.get(policy)?.jsonPrimitive?.booleanOrNull ?: default

internal fun expired(timestamp: String): Boolean = !Instant.parse(timestamp).isAfter(Instant.now())

fun Tx.createAccount(tenantId: String, email: String, password: String, name: String): Record {
    val normalized = normalizedEmail(email)
    if (findAccount(tenantId, normalized) != null) conflict("Account already exists")
    val account = Account(normalized, normalizedName(name), Passwords.hash(password))
    return create("account", tenantId, data = body(account))
}

internal fun Record.profile(): Profile = decode<Account>().let { Profile(id, tenantId, it.email, it.name) }

internal fun Tx.revokeSessions(tenantId: String, userId: String) {
    list("session", tenantId, ownerId = userId).forEach {
        val session = it.decode<SessionData>()
        if (!session.revoked) update(it, body(session.copy(revoked = true)))
    }
}

internal fun parseToken(token: String): List<String>? = token.takeIf { it.length in 50..300 }
    ?.split('.')?.takeIf { it.size == 3 && it.all { part -> part.isNotBlank() } }

internal fun Tx.identityAudit(tenantId: String, userId: String?, action: String) {
    create("audit", tenantId, ownerId = userId, data = kotlinx.serialization.json.buildJsonObject {
        put("action", kotlinx.serialization.json.JsonPrimitive(action))
        userId?.let { put("actorId", kotlinx.serialization.json.JsonPrimitive(it)) }
    })
}
