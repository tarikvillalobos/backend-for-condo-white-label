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
    filters.text("nodeId")?.let { store.get("node", it, locationId) }
    filters.text("since")?.let(::timestamp); filters.text("until")?.let(::timestamp)
    val row = save("export", input.merge(obj("status" to "queued", "rowCount" to null, "expiresAt" to null, "attempts" to 0,
        "sourceRequestId" to requestId, "filters" to filters)))
    return V1Response(exportView(row), 202)
}
private data class PendingExport(val id: String, val tenant: String, val brand: String, val location: String, val user: String)
fun processReportExports(db: Database) {
    val pending = db.scopedTx(null) { tx ->
        val queued = if (tx.postgres) "payload::jsonb ->> 'status' IN ('queued','processing') AND payload::jsonb ->> '_deletedAt' IS NULL"
            else "(payload LIKE '%\"status\":\"queued\"%' OR payload LIKE '%\"status\":\"processing\"%')"
        tx.connection.prepareStatement("SELECT * FROM app_records WHERE kind = 'v1_export' AND $queued ORDER BY created_at LIMIT 200").use { statement ->
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) {
                    val data = json.parseToJsonElement(rows.getString("payload")).jsonObject
                    val status = data.text("status")
                    val stale = status == "processing" && data.text("leaseUntil")?.let { timestamp(it).isBefore(Instant.now()) } == true
                    if (data.text("_deletedAt") == null && (status == "queued" || stale)) add(PendingExport(data.text("_id")!!,
                        rows.getString("tenant_id"), data.text("_brandId")!!, rows.getString("location_id"), rows.getString("owner_id")))
                }
            } }
        }
    }
    pending.take(10).forEach { job -> processExport(db, job) }
}
private fun processExport(db: Database, job: PendingExport) {
    fun context(tx: Tx) = V1Context(tx, "processReportExport", job.tenant, job.brand, UUID.randomUUID().toString(),
        principal = V1Principal(userId = job.user, staff = true, permissions = setOf("*")), locationId = job.location)
    val claimed = db.scopedTx("export:${job.id}") { tx ->
        val c = context(tx)
        val row = c.store.find("export", job.id, job.location) ?: return@scopedTx false
        if (row.data.text("status") !in setOf("queued", "processing")) return@scopedTx false
        if (row.data.text("status") == "processing" && row.data.text("leaseUntil")?.let { timestamp(it).isAfter(c.now) } == true) return@scopedTx false
        c.store.update(row, row.data.merge(obj("status" to "processing", "leaseUntil" to c.now.plusSeconds(300).toString(), "attempts" to row.data.number("attempts") + 1)))
        true
    }
    if (!claimed) return
    runCatching {
        db.scopedTx("export:${job.id}") { tx ->
            val c = context(tx)
            val row = c.store.get("export", job.id, job.location)
            if (tx.get("account", job.user, job.tenant)?.data?.get("active") == JsonPrimitive(false)) c.fail(403, "ACCOUNT_DISABLED", "Conta desativada")
            val records = c.exportRows(row)
            val format = row.data.text("format")!!
            val bytes = if (format == "xlsx") xlsx(records) else csv(records)
            val contentType = if (format == "xlsx") "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" else "text/csv"
            val file = writePrivateFile(c, "${row.data.text("resource")}-${row.id}.$format", contentType, bytes)
            val updated = c.store.update(row, row.data.merge(obj("status" to "ready", "rowCount" to records.size,
                "fileKey" to file.id, "expiresAt" to c.now.plusSeconds(86400).toString(), "leaseUntil" to null)))
            c.audit("export.completed", updated)
        }
    }.onFailure {
        db.scopedTx("export:${job.id}") { tx ->
            val c = context(tx)
            val row = c.store.find("export", job.id, job.location) ?: return@scopedTx
            val failed = c.store.update(row, row.data.merge(obj("status" to "failed", "failureCode" to ((it as? ApiException)?.code ?: "EXPORT_FAILED"), "leaseUntil" to null)))
            c.audit("export.failed", failed)
        }
    }
}
