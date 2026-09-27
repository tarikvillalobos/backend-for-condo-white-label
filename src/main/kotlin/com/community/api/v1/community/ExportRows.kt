package com.community.api.v1.community

import com.community.api.core.json
import com.community.api.v1.*
import kotlinx.serialization.json.*

private val columns = mapOf(
    "parcels" to listOf("id", "reference", "nodeId", "membershipId", "carrier", "trackingCode", "storage", "status", "depositedAt", "deadline", "collectedAt"),
    "memberships" to listOf("id", "userId", "nodeId", "role", "status", "createdAt"),
    "visitors" to listOf("id", "name", "kind", "company", "vehiclePlate", "createdAt"),
    "access_events" to listOf("id", "gateId", "direction", "method", "subjectName", "nodeId", "vehiclePlate", "occurredAt", "recordedByName"),
    "reservations" to listOf("id", "spaceId", "nodeId", "membershipId", "status", "startsAt", "endsAt", "createdAt"),
    "tickets" to listOf("id", "reference", "kind", "category", "title", "status", "priority", "dueAt", "createdAt", "resolvedAt"),
    "pets" to listOf("id", "nodeId", "name", "species", "breed", "sex", "birthDate", "createdAt"),
    "vehicles" to listOf("id", "nodeId", "plate", "model", "color", "kind", "parkingSpot", "createdAt"),
    "audit" to listOf("id", "action", "outcome", "requestId", "createdAt", "target", "actor"),
)
internal fun V1Context.exportRows(job: com.community.api.core.Record): List<Map<String, String>> {
    val resource = job.data.text("resource")!!
    val filters = job.data["filters"] as? JsonObject ?: obj()
