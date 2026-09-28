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
