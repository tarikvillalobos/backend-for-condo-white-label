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
    if (!data.active || data.type != type || data.locationId != locationId || record.locationId != locationId) unauthorized()
    if (!MessageDigest.isEqual(data.tokenHash.toByteArray(), digest(token).toByteArray())) unauthorized()
    val client = tx.requireRecord("client", record.tenantId, record.tenantId)
    val location = tx.requireRecord("location", locationId, record.tenantId)
    for (resource in listOf(client, location)) {
        if (resource.data["active"]?.jsonPrimitive?.booleanOrNull != true) forbidden()
        if ("packages" !in json.decodeFromJsonElement<Set<String>>(resource.data["features"] ?: forbidden())) forbidden()
    }
    return Context(Actor("integration:${record.id}", record.tenantId, record.id), locationId, setOf("packages.collect"))
}

fun Route.integrationRoutes(db: Database) {
    route("/api/v1/locations/{locationId}/integrations") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.parameters["locationId"]!!, "integrations.manage")
                tx.list("integration", ctx.tenantId, ctx.locationId).map { record ->
                    val data = record.decode<IntegrationData>()
                    IntegrationView(record.id, data.provider, data.type, data.active)
                }
            })
        }
        post {
            val input = call.receive<IntegrationRequest>()
            input.provider.validText("provider", 100)
            if (input.type != "locker") badRequest("Only locker event integrations are supported")
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.parameters["locationId"]!!, "integrations.manage", "packages")
                tx.requireRecentAuthentication(ctx.actor)
                val id = UUID.randomUUID().toString()
                val token = "${ctx.tenantId}.$id.${secret()}"
                tx.create("integration", ctx.tenantId, ctx.locationId, data = body(IntegrationData(input.provider, ctx.locationId!!, digest(token))), id = id)
                tx.audit(ctx, "integration.created", id)
                IntegrationIssued(id, token)
            })
        }
        post("/{id}/rotate") {
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.parameters["locationId"]!!, "integrations.manage")
                tx.requireRecentAuthentication(ctx.actor)
