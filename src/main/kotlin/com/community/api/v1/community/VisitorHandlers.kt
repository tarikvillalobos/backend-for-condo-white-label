package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun V1Context.visitor(row: Record): JsonObject = view("Visitor", row, obj(
    "document" to row.data.text("document")?.let { "***${it.takeLast(4)}" }, "phone" to row.data["phone"],
    "company" to row.data["company"], "vehiclePlate" to row.data["vehiclePlate"], "notes" to row.data["notes"],
))
internal fun V1Context.inviteStatus(row: Record): String = when {
    row.data.text("revokedAt") != null -> "revoked"
    (row.data.flag("singleUse") && row.data.number("usesCount") > 0) ||
        row.data.text("maxUses")?.toIntOrNull()?.let { row.data.number("usesCount") >= it } == true -> "used"
    !timestamp(row.data.text("validUntil")!!).isAfter(now) -> "expired"
    timestamp(row.data.text("validFrom")!!).isAfter(now) -> "scheduled"
    else -> "active"
}
internal fun V1Context.invite(row: Record): JsonObject {
    val visitorId = row.data.text("visitorId")!!
