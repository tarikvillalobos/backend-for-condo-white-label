package com.community.api.core

import com.community.api.identity.requireRecentAuthentication
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

@Serializable
data class IntegrationData(val provider: String, val locationId: String, val tokenHash: String, val active: Boolean = true, val type: String = "locker")

@Serializable
data class IntegrationRequest(val provider: String, val type: String = "locker")

@Serializable
data class IntegrationIssued(val id: String, val token: String)

@Serializable
data class IntegrationView(val id: String, val provider: String, val type: String, val active: Boolean)

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
private fun secret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

fun ApplicationCall.integration(tx: Tx, locationId: String, type: String = "locker"): Context {
    val header = request.headers["Authorization"].orEmpty()
    if (!header.startsWith("Bearer ", true) || header.length > 1024) unauthorized()
    val token = header.substring(7)
    val parts = token.split('.')
    if (parts.size != 3) unauthorized()
    val record = tx.get("integration", parts[1], parts[0]) ?: unauthorized()
    val data = record.decode<IntegrationData>()
