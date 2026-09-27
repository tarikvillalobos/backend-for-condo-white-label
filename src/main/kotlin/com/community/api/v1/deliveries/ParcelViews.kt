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
    val credentialStatus = if (data.text("credentialStatus") == "active" && !instant(data.text("credentialExpiresAt")).isAfter(now)) "expired" else data.text("credentialStatus")
    val lockerView = locker?.let { obj("id" to it.id, "name" to it.data.text("name"), "address" to it.data.text("address").orEmpty(), "available" to it.data.flag("available")) }
    return obj("id" to row.id, "recipientId" to (data.text("membershipId") ?: data.text("nodeId")),
        "membershipId" to data.text("membershipId"), "carrier" to data.text("carrier"), "tracking" to data["tracking"], "status" to status,
        "locker" to lockerView, "compartment" to data["compartmentCode"], "storage" to data.text("storage"), "size" to data["size"],
        "depositedAt" to data.text("depositedAt"), "notifiedAt" to data["notifiedAt"], "deadline" to data.text("deadline"),
        "manualAt" to data["manualAt"], "collectedAt" to data["collectedAt"], "credentialStatus" to credentialStatus,
        "actions" to obj("canMarkManually" to (status == "waiting"), "canUndoManual" to (status == "manual" && undoUntil!!.isAfter(now)),
            "undoUntil" to undoUntil?.toString(), "canReportIssue" to (status !in setOf("returned", "cancelled"))),
        "timeline" to data.array("timeline"), "version" to row.version, "recipientKind" to data.text("recipientKind"),
        "node" to node(data.text("nodeId")), "delegates" to JsonArray(data.array("delegates").map { person(it.jsonPrimitive.content) }),
        "collectedBy" to person(data.text("collectedBy")))
}

internal fun V1Context.pickupView(row: Record): JsonObject {
    val data = row.data
    if (data.text("status") != "waiting" || data.text("credentialStatus") != "active" || !instant(data.text("credentialExpiresAt")).isAfter(now))
        fail(410, "CREDENTIAL_EXPIRED", "Pickup credential is no longer active")
    if (membershipId != null && data.text("credentialMemberId") != membershipId) fail(403, "FORBIDDEN", "Credential belongs to another collector")
    data.text("lockerId")?.let {
        val locker = store.get("locker", it, row.locationId)
        if (!locker.data.flag("available") || !lockerOnline(locker.data, now)) fail(503, "LOCKER_UNAVAILABLE", "Locker is unavailable")
    }
    val expiry = instant(data.text("credentialExpiresAt"))
    val code = unseal(data.text("sealedCode") ?: fail(410, "CREDENTIAL_REVOKED", "Credential is unavailable"))
    return obj("parcelId" to row.id, "membershipId" to data.text("credentialMemberId"), "code" to code, "qrPayload" to code,
        "status" to "active", "verifiedAt" to now.toString(), "expiresAt" to expiry.toString(),
        "revalidateAfter" to minOf(expiry, now.plusSeconds(60)).toString())
}
