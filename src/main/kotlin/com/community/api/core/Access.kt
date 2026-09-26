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
    "notifications.read", "notifications.manage", "cameras.view",
)

private val conciergePermissions = setOf(
    "packages.read.all", "packages.receive", "packages.collect", "lockers.read", "visitors.read.all",
    "visitors.checkin", "visitors.manage", "vehicles.read.all", "contacts.read", "announcements.read",
)

private val managerPermissions = residentPermissions + conciergePermissions + setOf(
    "locations.read", "locations.manage", "units.manage", "memberships.read", "memberships.manage",
    "packages.manage", "lockers.manage", "reservations.read.all", "reservations.manage", "facilities.manage",
    "announcements.manage", "events.manage", "pets.read.all", "pets.manage", "requests.read.all",
    "requests.manage", "vehicles.manage", "maintenance.read", "maintenance.manage", "documents.manage",
    "contacts.manage", "cameras.manage", "reports.read", "audit.read", "staff.manage", "parking.manage", "maintenance.work", "attachments.read.all",
)

val roleTemplates = mapOf(
    "client_admin" to setOf("*"),
    "property_manager" to managerPermissions,
    "concierge" to conciergePermissions,
    "operational_staff" to setOf("maintenance.read", "maintenance.work", "requests.read.all", "requests.manage", "contacts.read"),
    "resident" to residentPermissions,
    "auditor" to setOf("packages.read.all", "reservations.read.all", "requests.read.all", "reports.read", "audit.read"),
)

fun ApplicationCall.actor(tx: Tx): Actor {
    val header = request.headers["Authorization"] ?: unauthorized()
    if (!header.startsWith("Bearer ", ignoreCase = true)) unauthorized()
    val token = header.substring(7)
    if (token.isBlank() || token.length > 1024) unauthorized()
    return tx.authenticate(token)
}

fun Tx.requireRecord(kind: String, id: String, tenantId: String, locationId: String? = null): Record {
    val record = get(kind, id, tenantId) ?: notFound()
    if (locationId != null && record.locationId != locationId) notFound()
    return record
}

fun Tx.memberPermissions(tenantId: String, membership: Membership): Set<String> {
    val role = roleTemplates[membership.role]
        ?: list("role", tenantId).firstOrNull { it.decode<RoleDefinition>().name == membership.role }?.decode<RoleDefinition>()?.permissions
        ?: emptySet()
    return role + membership.permissions
}

fun Tx.activeMemberships(tenantId: String, userId: String): List<Record> =
    list("membership", tenantId, ownerId = userId).filter { it.decode<Membership>().current() }

fun Tx.requireMember(tenantId: String, locationId: String, userId: String) {
    if (get("account", userId, tenantId)?.data?.get("active")?.jsonPrimitive?.booleanOrNull != true) notFound()
    if (activeMemberships(tenantId, userId).none { it.locationId == null || it.locationId == locationId }) notFound()
}

private fun Tx.context(actor: Actor, locationId: String?, feature: String?): Context {
    val client = get("client", actor.tenantId, actor.tenantId) ?: forbidden()
    if (client.data["active"]?.jsonPrimitive?.booleanOrNull != true) forbidden()
    if (get("account", actor.userId, actor.tenantId)?.data?.get("active")?.jsonPrimitive?.booleanOrNull != true) forbidden()
    val memberships = activeMemberships(actor.tenantId, actor.userId)
        .filter { it.locationId == null || (locationId != null && it.locationId == locationId) }
