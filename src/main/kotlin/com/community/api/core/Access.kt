package com.community.api.core

import com.community.api.identity.authenticate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

val allFeatures = setOf(
    "packages", "reservations", "announcements", "events", "pets", "requests", "visitors",
    "vehicles", "maintenance", "documents", "cameras", "notifications", "contacts",
)

@Serializable
data class Membership(
    val userId: String,
    val locationId: String? = null,
    val unitId: String? = null,
    val role: String = "resident",
    val permissions: Set<String> = emptySet(),
    val active: Boolean = true,
    val expiresAt: String? = null,
) {
    fun current(): Boolean = active && (expiresAt == null || Instant.parse(expiresAt).isAfter(Instant.now()))
}

@Serializable
data class RoleDefinition(val name: String, val permissions: Set<String>)

private val residentPermissions = setOf(
    "locations.read", "attachments.read.own", "attachments.create",
    "packages.read.own", "reservations.read.own", "reservations.create", "facilities.read",
    "announcements.read", "events.read", "events.attend", "pets.read.own", "pets.create", "pets.manage.own",
    "requests.create", "requests.read.own", "requests.comment", "visitors.create", "visitors.read.own",
    "vehicles.read.own", "vehicles.create", "vehicles.manage.own", "documents.read", "contacts.read",
