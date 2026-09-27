package com.community.api.v1.deliveries

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun V1Context.parcelView(row: Record, admin: Boolean = false): JsonObject {
    val data = row.data
    val locker = data.text("lockerId")?.let { store.get("locker", it, row.locationId) }
    val status = data.text("status")
    if (admin) return obj("id" to row.id, "status" to status, "recipientKind" to data.text("recipientKind"),
        "recipient" to person(data.text("membershipId")), "node" to node(data.text("nodeId")), "nodePath" to nodePath(data.text("nodeId")),
        "carrier" to data.text("carrier"), "tracking" to data["tracking"], "size" to data["size"], "storage" to data.text("storage"),
        "lockerName" to locker?.data?.text("name"), "compartmentCode" to data["compartmentCode"], "depositedAt" to data.text("depositedAt"),
        "deadline" to data.text("deadline"), "overdue" to (status in setOf("waiting", "manual") && instant(data.text("deadline")).isBefore(now)),
        "collectedAt" to data["collectedAt"], "collectedByName" to (person(data.text("collectedBy")) as? JsonObject)?.text("name"),
        "registeredByName" to data["registeredByName"], "notes" to data["notes"], "photoUrls" to data.array("photoUrls"),
        "timeline" to data.array("timeline"), "version" to row.version)
    val manualAt = data.text("manualAt")?.let(::instant)
    val undoUntil = manualAt?.plusSeconds(600)
