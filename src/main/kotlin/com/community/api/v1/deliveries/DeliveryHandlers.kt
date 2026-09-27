package com.community.api.v1.deliveries

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.Duration

fun deliveryHandlers(): Map<String, V1Handler> = mapOf(
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
