package com.community.api.v1

import com.community.api.core.*
import kotlinx.serialization.json.*
import java.time.Instant

fun auditHandlers(): Map<String,V1Handler> = mapOf(
    "listAuditLog" to V1Handler { V1Response(it.auditPage("audit_log")) },
    "listOrganizationAuditLog" to V1Handler { V1Response(it.auditPage("audit_log")) },
    "listBrandAuditLog" to V1Handler { V1Response(it.auditPage("audit_log")) },
    "listRequestLog" to V1Handler { V1Response(it.auditPage("api_requests")) },
    "listOrganizationRequestLog" to V1Handler { V1Response(it.auditPage("api_requests")) },
    "listDataChanges" to V1Handler { V1Response(it.auditPage("audit_changes")) },
    "listBrandDataChanges" to V1Handler { V1Response(it.auditPage("audit_changes")) },
    "getAuditEntry" to V1Handler { c ->
        val entry = c.auditRow("audit_log",c.path.getValue("entryId"))
        val notes = c.store.list("audit_note",c.locationId,filters=mapOf("auditEntryId" to c.path.getValue("entryId"))).map { c.project("AuditNote",it.document()) }
        V1Response(JsonObject(entry+obj("notes" to notes)))
    },
    "getRequestTrace" to V1Handler { c ->
        val id = c.path.getValue("requestId")
        V1Response(obj("request" to c.auditRow("api_requests",id),"events" to c.auditRows("audit_log",mapOf("request_id" to id)),"changes" to c.auditRows("audit_changes",mapOf("request_id" to id))))
    },
    "getObjectHistory" to V1Handler { c ->
        val rows = c.auditRows("audit_log",mapOf("target_type" to c.path.getValue("targetType"),"target_id" to c.path.getValue("targetId")))
        val history = rows.map { row -> obj("at" to row["createdAt"],"layer" to "event","requestId" to row["requestId"],"actor" to row["actor"],"summary" to row["action"],"data" to row) }
        V1Response(c.pageItems(history))
    },
    "verifyAuditIntegrity" to V1Handler { c -> V1Response(c.verifyChain()) },
    "reportAuditEvents" to V1Handler { c -> V1Response(obj("items" to c.input["events"]!!.jsonArray.mapIndexed { index,event -> c.acceptClientEvent(index,event.jsonObject) }),202) },
    "addAuditNote" to V1Handler { c ->
        c.auditRow("audit_log",c.path.getValue("entryId"))
        val row = c.store.create("audit_note",obj("auditEntryId" to c.path.getValue("entryId"),"authorName" to c.userId,
            "authorRole" to "staff","body" to c.input["body"]),c.locationId,c.userId)
        V1Response(c.project("AuditNote",row.document()),201)
    },
    "listRetentionHolds" to V1Handler { c ->
        val rows = c.store.list("retention_hold").filter { c.canReadHold(it) }
        V1Response(obj("items" to rows.map { c.project("RetentionHold",it.document()) }))
    },
    "createRetentionHold" to V1Handler { c ->
        val since = Instant.parse(c.input.string("since")); val until = Instant.parse(c.input.string("until"))
        if (!since.isBefore(until)) c.fail(422,"VALIDATION_ERROR","since must precede until")
        c.input.string("condominiumId")?.let { c.store.get("condominium",it) }
        c.input.string("organizationId")?.let { c.store.get("organization",it) }
        if ("*" !in c.principal!!.permissions && "brand.audit" !in c.principal.permissions) {
            val org = c.input.string("organizationId") ?: c.fail(403,"ACCESS_DENIED","Organization scope required")
            if (org !in c.ownOrganizations()) c.fail(403,"ACCESS_DENIED","Organization scope required")
            c.input.string("condominiumId")?.let { condo -> if (c.store.list("organization_condominium",filters=mapOf("organizationId" to org,"status" to "active")).none { it.locationId==condo }) c.fail(404,"RESOURCE_NOT_FOUND","Condominium not found") }
        }
        val row = c.store.create("retention_hold",JsonObject(c.input+obj("createdByName" to c.userId,"releasedAt" to null,"releasedByName" to null,"releaseReason" to null)),c.input.string("condominiumId"),c.userId)
        V1Response(c.project("RetentionHold",row.document()),201)
    },
    "releaseRetentionHold" to V1Handler { c ->
        val row = c.store.get("retention_hold",c.path.getValue("holdId"))
        if (row.data.string("releasedAt") != null) c.fail(409,"VERSION_CONFLICT","Hold already released")
        val updated = c.store.update(row,JsonObject(row.data+obj("releasedAt" to c.now,"releasedByName" to c.userId,"releaseReason" to c.input["reason"])))
        V1Response(c.project("RetentionHold",updated.document()))
    },
)

private fun V1Context.ownOrganizations(): Set<String> = store.list("staff_assignment",ownerId=userId)
    .filter { it.data.string("status") == "active" }.mapNotNull { it.data.string("organizationId") }.toSet()
private fun V1Context.canReadHold(row: Record): Boolean = "*" in principal!!.permissions || "brand.audit" in principal.permissions || row.data.string("organizationId") in ownOrganizations()

private fun V1Context.auditWhere(table: String, extra: Map<String,String>): Pair<String,List<Any>> {
    require(table in setOf("audit_log","api_requests","audit_changes"))
    val sql = StringBuilder("tenant_id = ? AND brand_id = ?")
    val values = mutableListOf<Any>(tenantId,brandId)
    if (locationId != null) { sql.append(" AND location_id = ?"); values += locationId }
    path["organizationId"]?.let { org ->
        val condos = store.list("organization_condominium",filters=mapOf("organizationId" to org,"status" to "active")).mapNotNull { it.locationId }
        if (condos.isEmpty()) sql.append(" AND 1 = 0")
        else { sql.append(" AND location_id IN (${condos.joinToString(",") { "?" }})"); values.addAll(condos) }
    }
    extra.forEach { (key,value) ->
        require(key in setOf("request_id","id","target_type","target_id","row_id"))
        sql.append(" AND $key = ?"); values += value
    }
    return sql.toString() to values
