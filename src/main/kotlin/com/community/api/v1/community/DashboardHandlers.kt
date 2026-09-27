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
    val lockers = reportRows("locker")
    val compartments = lockers.flatMap { it.data.array("compartments").map(JsonElement::jsonObject) }
    val cameras = reportRows("camera")
    val members = reportRows("membership")
    return obj("generatedAt" to now.toString(), "parcels" to obj("waiting" to parcels.count { it.data.text("status") == "waiting" },
        "overdue" to parcels.count { it.data.text("status") == "waiting" && it.data.text("deadline")?.let { time -> timestamp(time).isBefore(now) } == true },
        "receivedToday" to parcels.count { today(it.data.text("depositedAt")) }, "collectedToday" to parcels.count { today(it.data.text("collectedAt")) }),
        "access" to obj("entriesToday" to access.count { it.data.text("direction") == "entry" && today(it.data.text("occurredAt")) }, "activeInvites" to invites.count { inviteStatus(it) == "active" }),
        "tickets" to obj("open" to tickets.size, "supportIssues" to tickets.count { it.data.text("kind") == "support_issue" },
            "serviceRequests" to tickets.count { it.data.text("kind") == "service_request" }, "occurrences" to tickets.count { it.data.text("kind") == "occurrence" }),
        "reservations" to obj("today" to reservations.count { today(it.data.text("startsAt")) && it.data.text("status") !in setOf("cancelled", "rejected") },
            "pendingApproval" to reservations.count { it.data.text("status") == "pending" }),
        "lockers" to obj("total" to lockers.size, "offline" to lockers.count { it.data.text("status") == "offline" || !it.data.flag("available", true) },
            "compartments" to compartments.size, "occupied" to compartments.count { it.text("status") == "occupied" }, "faulty" to compartments.count { it.text("status") == "faulty" }),
        "cameras" to obj("total" to cameras.size, "offline" to cameras.count { it.data.text("status") == "offline" }),
        "memberships" to obj("active" to members.count { it.data.text("status") == "active" }, "pending" to members.count { it.data.text("status") in setOf("pending", "invited") }))
}
private fun V1Context.residentDashboard(): JsonObject {
    fun own(kind: String) = store.list(kind, locationId, userId, mapOf("membershipId" to membershipId!!))
    val unreadAnnouncements = store.list("announcement", locationId).count { row -> visible(row.data) &&
        !timestamp(row.data.text("publishedAt")!!).isAfter(now) && row.data.text("expiresAt")?.let { timestamp(it).isAfter(now) } != false && receipt(row.id) == null }
    return obj("pendingParcels" to own("parcel").count { it.data.text("status") == "waiting" },
        "unreadNotifications" to own("notification").count { it.data.text("readAt") == null }, "unreadAnnouncements" to unreadAnnouncements,
        "activeInvites" to own("access_invite").count { inviteStatus(it) == "active" },
        "upcomingReservations" to own("reservation").count { it.data.text("status") !in setOf("cancelled", "rejected") && timestamp(it.data.text("startsAt")!!).isAfter(now) },
        "openRequests" to own("ticket").count { it.data.text("kind") == "service_request" && it.data.text("status") !in closedTicketStates },
        "activePetAlerts" to store.list("pet_alert", locationId, filters = mapOf("status" to "open")).size, "generatedAt" to now.toString())
}
internal fun dashboardHandlers(): Map<String, V1Handler> = mapOf(
    "getDashboard" to V1Handler { c -> V1Response(c.residentDashboard()) },
    "adminDashboard" to V1Handler { c -> V1Response(c.adminDashboard()) },
    "organizationDashboard" to V1Handler { c -> c.organizationDashboard() },
    "organizationWorkQueue" to V1Handler { c -> c.organizationQueue() },
)
