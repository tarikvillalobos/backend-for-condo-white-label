package com.community.api.v1.deliveries

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.Duration

fun deliveryHandlers(): Map<String, V1Handler> = (mapOf(
    "listParcels" to V1Handler(::listParcels), "adminListParcels" to V1Handler(::listParcels),
    "organizationListParcels" to V1Handler(::listParcels),
    "getParcel" to V1Handler { c -> V1Response(c.parcelView(c.parcel())) },
    "adminGetParcel" to V1Handler { c -> V1Response(c.parcelView(c.parcel(), true)) },
    "getParcelMetrics" to V1Handler(::metrics), "getPickupCredential" to V1Handler { c -> V1Response(c.pickupView(c.parcel())) },
    "markManualPickup" to V1Handler(::manualPickup), "undoManualPickup" to V1Handler(::manualPickup),
    "addParcelDelegate" to V1Handler(::delegate), "removeParcelDelegate" to V1Handler(::delegate),
    "registerParcel" to V1Handler(::registerParcel), "handoverParcel" to V1Handler(::handover),
    "adminUpdateParcel" to V1Handler(::updateParcel), "adminExtendParcelDeadline" to V1Handler(::updateParcel),
    "adminReturnParcel" to V1Handler(::closeParcel), "adminCancelParcel" to V1Handler(::closeParcel),
    "adminResendParcelNotice" to V1Handler(::resendNotice), "adminReissuePickupCredential" to V1Handler(::reissue),
    "listSupportIssues" to V1Handler(::supportIssues), "createSupportIssue" to V1Handler(::createSupportIssue),
    "getSupportIssue" to V1Handler(::getSupportIssue),
) + lockerHandlers()

private fun listParcels(c: V1Context): V1Response {
    val admin = c.operationId != "listParcels"
    val rows = c.store.list("parcel", c.locationId).filter { row ->
        val data = row.data
        val status = data.text("status")
        val filter = c.query["status"] ?: if (admin) "waiting" else "all"
        (admin || c.canReadParcel(row)) && (if (admin) status == filter else when (filter) {
            "waiting" -> status == "waiting"; "collected" -> status in setOf("manual", "collected"); else -> true
        }) && (c.query["storage"] == null || data.text("storage") == c.query["storage"]) &&
            (c.query["overdue"] != "true" || (status in setOf("waiting", "manual") && instant(data.text("deadline")).isBefore(c.now))) &&
            (c.query["nodeId"] == null || c.nodePath(data.text("nodeId")).any { it.jsonObject.text("id") == c.query["nodeId"] }) &&
            (c.query["since"] == null || !instant(data.text("depositedAt")).isBefore(instant(c.query["since"]))) &&
            (c.query["until"] == null || instant(data.text("depositedAt")).isBefore(instant(c.query["until"]))) &&
            (c.query["q"].isNullOrBlank() || listOf(data.text("carrier"), data.text("tracking"), (c.person(data.text("membershipId")) as? JsonObject)?.text("name"))
                .any { it?.contains(c.query.getValue("q"), true) == true })
    }.sortedWith(compareByDescending<Record> { it.data.text("depositedAt") }.thenBy { it.id })
    var page = c.pageItems(rows.map { c.parcelView(it, admin) })
    if (admin) page = page.changed("totals" to obj("waiting" to rows.count { it.data.text("status") == "waiting" },
        "overdue" to rows.count { it.data.text("status") in setOf("waiting", "manual") && instant(it.data.text("deadline")).isBefore(c.now) }))
    return V1Response(page)
}

private fun metrics(c: V1Context): V1Response {
    val until = c.query["until"]?.let(::instant) ?: c.now
    val since = c.query["since"]?.let(::instant) ?: until.minusSeconds(30L * 86400)
    if ((c.query["since"] == null) != (c.query["until"] == null) || !since.isBefore(until) || until.isAfter(c.now) || Duration.between(since, until).toDays() > 366)
        c.fail(422, "VALIDATION_ERROR", "Use a positive period of at most 366 days ending no later than now")
    val rows = c.store.list("parcel", c.locationId).filter {
        c.canReadParcel(it) && !instant(it.data.text("depositedAt")).isBefore(since) && instant(it.data.text("depositedAt")).isBefore(until)
    }
    val durations = rows.mapNotNull { row -> row.data.text("collectedAt")?.let(::instant)?.takeIf { it.isBefore(until) }?.let {
        Duration.between(instant(row.data.text("depositedAt")), it).seconds.toDouble()
    } }.filter { it >= 0 }
    return V1Response(obj("since" to since.toString(), "until" to until.toString(), "generatedAt" to c.now.toString(), "complete" to true,
        "totalReceived" to rows.size, "physicalPickupCount" to durations.size, "averagePickupDurationSeconds" to durations.takeIf { it.isNotEmpty() }?.average()))
}

private fun manualPickup(c: V1Context): V1Response {
    val row = c.parcel()
    c.requireVersion(row)
    c.outstanding(row)
    val undo = c.operationId == "undoManualPickup"
    if (!undo && row.data.text("status") == "manual") return V1Response(c.parcelView(row))
    if (undo && (row.data.text("status") != "manual" || !instant(row.data.text("manualAt")).plusSeconds(600).isAfter(c.now)))
        c.fail(409, "MANUAL_UNDO_EXPIRED", "Manual pickup can no longer be reversed")
    val updated = c.store.update(row, row.data.changed("status" to JsonPrimitive(if (undo) "waiting" else "manual"),
        "manualAt" to (if (undo) JsonNull else JsonPrimitive(c.now.toString())), "credentialStatus" to JsonPrimitive("revoked"),
        "sealedCode" to JsonNull, "credentialHash" to JsonNull,
        "timeline" to timeline(row.data, if (undo) "manual_pickup_reverted" else "manual_pickup", c.now)))
    c.audit(if (undo) "parcel.manual_pickup_reverted" else "parcel.manual_pickup", updated)
    return V1Response(c.parcelView(updated), headers = mapOf("ETag" to "\"${updated.version}\""))
}

private fun delegate(c: V1Context): V1Response {
    val row = c.parcel()
    c.recipient(row)
    c.outstanding(row)
    c.requireVersion(row)
    val id = c.path["delegateMembershipId"] ?: c.input.text("membershipId")!!
    val removing = c.operationId == "removeParcelDelegate"
    if (!removing) { c.member(id); if (id == c.membershipId) c.fail(422, "DELEGATE_NOT_ELIGIBLE", "Recipient does not need delegation") }
    val delegates = row.data.array("delegates").map { it.jsonPrimitive.content }.toMutableSet()
    if (removing) delegates.remove(id) else delegates.add(id)
    val updated = c.store.update(row, row.data.changed("delegates" to JsonArray(delegates.map(::JsonPrimitive)),
        "credentialStatus" to JsonPrimitive("revoked"), "sealedCode" to JsonNull, "credentialHash" to JsonNull))
    c.audit(if (removing) "parcel.delegate_removed" else "parcel.delegate_added", updated)
    return V1Response(c.parcelView(updated))
}

private fun supportView(row: Record): JsonObject = obj("id" to row.id, "reference" to row.data.text("reference"),
    "membershipId" to row.data.text("membershipId"), "parcelId" to row.data.text("parcelId"), "message" to row.data.text("message"),
    "status" to row.data.text("status"), "createdAt" to row.createdAt, "updatedAt" to row.updatedAt, "resolution" to row.data["resolution"])
private fun supportIssues(c: V1Context): V1Response = V1Response(c.page("ticket", filters = mapOf("kind" to "support_issue", "membershipId" to c.membershipId!!), transform = ::supportView))
private fun createSupportIssue(c: V1Context): V1Response {
    c.parcel(c.input.text("parcelId")!!)
    val message = c.input.text("message")!!.trim()
    if (message.length !in 10..2000) c.fail(422, "VALIDATION_ERROR", "Message must contain 10 to 2000 characters after trimming")
    val reference = "P-${java.util.UUID.randomUUID().toString().take(8).uppercase()}"
    val row = c.store.create("ticket", c.input.changed("kind" to JsonPrimitive("support_issue"), "message" to JsonPrimitive(message),
        "description" to JsonPrimitive(message), "membershipId" to JsonPrimitive(c.membershipId!!), "status" to JsonPrimitive("received"),
        "resolution" to JsonNull, "reference" to JsonPrimitive(reference)), c.locationId, c.userId)
    c.audit("parcel.issue_created", row)
    return V1Response(supportView(row), 201)
}
private fun getSupportIssue(c: V1Context): V1Response {
    val row = c.store.get("ticket", c.path.getValue("issueId"), c.locationId)
    if (row.data.text("kind") != "support_issue" || row.data.text("membershipId") != c.membershipId) c.fail(404, "NOT_FOUND", "Issue not found")
    return V1Response(supportView(row))
}
