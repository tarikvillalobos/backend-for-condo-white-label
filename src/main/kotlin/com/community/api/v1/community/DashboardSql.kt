package com.community.api.v1.community

import com.community.api.v1.*
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId

internal fun V1Context.postgresDashboard(): JsonObject {
    val zone = ZoneId.of(store.get("condominium", location()).data.text("timeZone") ?: "UTC")
    val start = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
    val end = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
    fun field(name: String) = "payload::jsonb ->> '$name'"
    fun at(name: String) = "(${field(name)})::timestamptz"
    fun today(name: String) = "${at(name)} >= '$start'::timestamptz AND ${at(name)} < '$end'::timestamptz"
    val waiting = "${field("status")} = 'waiting'"
    val open = "${field("status")} NOT IN ('resolved','closed','rejected','dismissed')"
    val activeInvites = "${field("revokedAt")} IS NULL AND ${at("validFrom")} <= '$now'::timestamptz AND ${at("validUntil")} > '$now'::timestamptz AND " +
        "(COALESCE((${field("singleUse")})::boolean,FALSE) = FALSE OR COALESCE((${field("usesCount")})::int,0) = 0) AND " +
        "(${field("maxUses")} IS NULL OR COALESCE((${field("usesCount")})::int,0) < (${field("maxUses")})::int)"
    val parcel = sqlCounts("parcel", linkedMapOf("waiting" to waiting, "overdue" to "$waiting AND ${at("deadline")} < '$now'::timestamptz",
        "receivedToday" to today("depositedAt"), "collectedToday" to today("collectedAt")))
    val access = sqlCounts("access_event", mapOf("entriesToday" to "${field("direction")} = 'entry' AND ${today("occurredAt")}"))
        .merge(sqlCounts("access_invite", mapOf("activeInvites" to activeInvites)))
    val tickets = sqlCounts("ticket", linkedMapOf("open" to open, "supportIssues" to "$open AND ${field("kind")} = 'support_issue'",
        "serviceRequests" to "$open AND ${field("kind")} = 'service_request'", "occurrences" to "$open AND ${field("kind")} = 'occurrence'"))
    val reservations = sqlCounts("reservation", mapOf("today" to "${today("startsAt")} AND ${field("status")} NOT IN ('cancelled','rejected')",
        "pendingApproval" to "${field("status")} = 'pending'"))
    val lockers = sqlCounts("locker", mapOf("total" to "TRUE", "offline" to "${field("status")} = 'offline' OR ${field("available")} = 'false'"))
        .merge(compartmentCounts())
    return obj("generatedAt" to now.toString(), "parcels" to parcel, "access" to access, "tickets" to tickets, "reservations" to reservations,
        "lockers" to lockers, "cameras" to sqlCounts("camera", mapOf("total" to "TRUE", "offline" to "${field("status")} = 'offline'")),
        "memberships" to sqlCounts("membership", mapOf("active" to "${field("status")} = 'active'", "pending" to "${field("status")} IN ('pending','invited')")))
}
private fun V1Context.sqlCounts(kind: String, conditions: Map<String, String>): JsonObject {
    val select = conditions.values.joinToString(",") { "COUNT(*) FILTER (WHERE $it)" }
    val sql = "SELECT $select FROM app_records WHERE kind = ? AND tenant_id = ? AND location_id = ? AND payload::jsonb ->> '_brandId' = ? AND payload::jsonb ->> '_deletedAt' IS NULL"
    return tx.connection.prepareStatement(sql).use { statement ->
        listOf("v1_$kind", tenantId, locationId, brandId).forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeQuery().use { rows -> rows.next(); obj(*conditions.keys.mapIndexed { index, key -> key to rows.getLong(index + 1) }.toTypedArray()) }
    }
}
