package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.util.UUID

private fun V1Context.organizationCondos(): List<Record> {
    val org = id("organizationId")
    store.get("organization", org)
    return store.list("organization_condominium", filters = mapOf("organizationId" to org, "status" to "active"))
        .mapNotNull { it.locationId }.distinct().map { store.get("condominium", it) }
        .filter { query["condominiumId"]?.let { requested -> it.id == requested } ?: true }
}
internal fun V1Context.atCondominium(id: String, operation: String = operationId) = V1Context(tx, operation, tenantId, brandId, requestId,
    input, path + ("condominiumId" to id), query, headers, principal, id, null, now)
internal fun V1Context.organizationDashboard(): V1Response {
    val rows = organizationCondos().map { condo -> obj("condominiumId" to condo.id, "name" to condo.data["name"],
        "dashboard" to atCondominium(condo.id).adminDashboard()) }
    val sections = listOf("parcels", "access", "tickets", "reservations", "lockers", "cameras", "memberships")
    val zero = mapOf("parcels" to listOf("waiting", "overdue", "receivedToday", "collectedToday"), "access" to listOf("entriesToday", "activeInvites"),
        "tickets" to listOf("open", "supportIssues", "serviceRequests", "occurrences"), "reservations" to listOf("today", "pendingApproval"),
        "lockers" to listOf("total", "offline", "compartments", "occupied", "faulty"), "cameras" to listOf("total", "offline"), "memberships" to listOf("active", "pending"))
    val totals = buildJsonObject {
        put("generatedAt", now.toString())
        sections.forEach { section -> put(section, buildJsonObject {
            zero.getValue(section).forEach { field -> put(field, rows.sumOf { it["dashboard"]!!.jsonObject[section]!!.jsonObject.number(field) }) }
        }) }
    }
    return V1Response(obj("generatedAt" to now.toString(), "totals" to totals, "byCondominium" to JsonArray(rows)))
}
internal fun V1Context.organizationQueue(): V1Response {
    val rows = organizationCondos().flatMap { condo -> atCondominium(condo.id).workItems(condo) }
    val counts = rows.groupingBy { it.text("kind")!! }.eachCount()
    val sorted = rows.filter { query["kind"]?.let { kind -> it.text("kind") == kind } ?: true }.sortedWith(
        compareBy<JsonObject> { if (it.text("kind") == "arrival_pending") 0 else 1 }
            .thenBy { when (it.text("priority")) { "urgent" -> 0; "high" -> 1; else -> 2 } }
            .thenBy { it.text("dueAt") ?: "9999" }.thenBy { it.text("createdAt") },
    )
    val page = pageItems(sorted)
    return V1Response(page.merge(obj("counts" to JsonObject(counts.mapValues { JsonPrimitive(it.value) }))))
}
private fun V1Context.workItems(condo: Record): List<JsonObject> = buildList {
    fun item(row: Record, kind: String, title: String, priority: String = "normal", due: String? = null) {
        add(obj("id" to UUID.nameUUIDFromBytes("${row.id}:$kind".toByteArray()).toString(), "kind" to kind,
            "condominiumId" to condo.id, "condominiumName" to condo.data["name"], "node" to node(row.data.text("nodeId")),
            "title" to title, "referenceId" to row.id, "priority" to priority, "createdAt" to row.createdAt, "dueAt" to due))
    }
    store.list("arrival", locationId, filters = mapOf("status" to "pending")).filter { timestamp(it.data.text("expiresAt")!!).isAfter(now) }
        .forEach { item(it, "arrival_pending", it.data.text("visitorName")!!, "urgent", it.data.text("expiresAt")) }
    store.list("parcel", locationId, filters = mapOf("status" to "waiting")).forEach {
        if (it.data.text("manualAt") != null) item(it, "parcel_manual_mismatch", "Retirada informada pelo morador", "high")
        if (timestamp(it.data.text("deadline")!!).isBefore(now)) item(it, "parcel_overdue", "Encomenda com prazo vencido", "high", it.data.text("deadline"))
    }
    store.list("reservation", locationId, filters = mapOf("status" to "pending")).forEach { item(it, "reservation_pending", "Reserva aguardando aprovação", due = it.data.text("startsAt")) }
    store.list("ticket", locationId).filter { it.data.text("status") !in closedTicketStates }.forEach {
        item(it, "ticket_open", it.data.text("title") ?: it.data.text("reference") ?: "Chamado", it.data.text("priority") ?: "normal", it.data.text("dueAt"))
    }
    store.list("camera", locationId, filters = mapOf("status" to "offline")).forEach { item(it, "camera_offline", it.data.text("name")!!, "high") }
