package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.ZoneId

internal val closedTicketStates = setOf("resolved", "closed", "rejected", "dismissed")
internal fun V1Context.reportRows(kind: String, scope: String? = query["nodeId"]): List<Record> = store.list(kind, locationId).filter {
    scope == null || inSubtree(it.data.text("nodeId"), scope)
}
internal fun V1Context.adminDashboard(): JsonObject {
    val timezone = store.get("condominium", location()).data.text("timeZone") ?: "UTC"
    val day = now.atZone(ZoneId.of(timezone)).toLocalDate()
    fun today(value: String?) = value?.let { timestamp(it).atZone(ZoneId.of(timezone)).toLocalDate() == day } ?: false
    val parcels = reportRows("parcel")
    val access = reportRows("access_event")
    val invites = reportRows("access_invite")
    val tickets = reportRows("ticket").filter { it.data.text("status") !in closedTicketStates }
    val reservations = reportRows("reservation")
