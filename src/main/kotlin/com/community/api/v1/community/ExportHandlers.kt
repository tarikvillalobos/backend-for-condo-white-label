package com.community.api.v1.community

import com.community.api.core.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

internal val exportKinds = mapOf("parcels" to "parcel", "memberships" to "membership", "visitors" to "visitor", "access_events" to "access_event",
    "reservations" to "reservation", "tickets" to "ticket", "pets" to "pet", "vehicles" to "vehicle", "audit" to "audit")
internal fun V1Context.exportView(row: Record): JsonObject = view("ExportJob", row, obj("rowCount" to row.data["rowCount"],
    "downloadUrl" to if (row.data.text("status") == "ready" && row.data.text("expiresAt")?.let { timestamp(it).isAfter(now) } == true)
        row.data.text("fileKey")?.let { fileUrl(it) } else null, "expiresAt" to row.data["expiresAt"]))
internal fun V1Context.createExport(): V1Response {
    val resource = input.text("resource")!!
    if (resource !in exportKinds) fail(422, "EXPORT_RESOURCE_INVALID", "Recurso inválido")
    if (resource == "audit") requirePermission("audit.read")
    val filters = input["filters"] as? JsonObject ?: obj()
    val allowed = setOf("nodeId", "status", "since", "until", "kind", "q", "species", "category")
    if (filters.keys.any { it !in allowed }) fail(422, "EXPORT_FILTER_INVALID", "Filtro de exportação não permitido")
