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
