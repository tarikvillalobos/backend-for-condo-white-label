package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal fun reportHandlers(): Map<String, V1Handler> = mapOf(
    "adminReport" to V1Handler { c -> c.report() },
    "adminCreateExport" to V1Handler { c -> c.createExport() },
    "adminGetExport" to V1Handler { c ->
        val row = c.store.get("export", c.id("exportId"), c.locationId)
        if (row.ownerId != c.userId) c.fail(404, "NOT_FOUND", "Exportação não encontrada")
        V1Response(c.exportView(row))
    },
)
private fun V1Context.report(): V1Response {
    val since = query["since"]?.let(::timestamp) ?: now.minusSeconds(30L * 86400)
    val until = query["until"]?.let(::timestamp) ?: now
    if (!until.isAfter(since) || Duration.between(since, until).toDays() > 366) fail(422, "REPORT_DATE_RANGE", "Informe um período de até 366 dias")
    val metric = id("metric")
    val group = query["groupBy"] ?: "day"
    val kind = mapOf("parcels" to "parcel", "access" to "access_event", "reservations" to "reservation", "tickets" to "ticket", "memberships" to "membership")[metric]
        ?: fail(422, "INVALID_METRIC", "Métrica inválida")
    val zone = ZoneId.of(store.get("condominium", location()).data.text("timeZone") ?: "UTC")
    val scope = query["nodeId"]
    val children = if (group == "node") store.list("node", locationId).filter { it.data.text("parentId") == scope } else emptyList()
    fun recordAt(row: Record) = timestamp(row.data.text("depositedAt") ?: row.data.text("occurredAt") ?: row.data.text("startsAt") ?: row.createdAt)
    fun key(row: Record): String {
        if (group == "node") return children.firstOrNull { inSubtree(row.data.text("nodeId"), it.id) }?.id ?: scope ?: "unassigned"
        val day = recordAt(row).atZone(zone).toLocalDate()
        return when (group) {
            "day" -> day.toString()
            "week" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
            "month" -> day.withDayOfMonth(1).toString()
            else -> fail(422, "INVALID_GROUP", "Agrupamento inválido")
        }
