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
    "reportAuditEvents" to V1Handler { c -> V1Response(obj("results" to c.input["events"]!!.jsonArray.mapIndexed { index,event -> c.acceptClientEvent(index,event.jsonObject) })) },
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
}

private fun V1Context.auditRows(table: String, extra: Map<String,String>): List<JsonObject> {
    val (where,values) = auditWhere(table,extra)
    return tx.connection.prepareStatement("SELECT payload FROM $table WHERE $where ORDER BY created_at LIMIT 5001").use {
        values.forEachIndexed { index,value -> it.setObject(index+1,value) }
        it.executeQuery().use { rows -> buildList { while(rows.next()) add(json.parseToJsonElement(rows.getString(1)).jsonObject) } }
    }
}
private fun V1Context.auditRow(table: String, id: String): JsonObject = auditRows(table,mapOf((if(table=="api_requests") "request_id" else "id") to id)).firstOrNull()
    ?: fail(404,"RESOURCE_NOT_FOUND","Audit record not found")

private fun V1Context.auditPage(table: String): JsonObject {
    val limit = query["limit"]?.toIntOrNull() ?: 20
    if (limit !in 1..100) fail(422,"VALIDATION_ERROR","Invalid limit")
    val binding = hash("$tenantId:$brandId:${principal?.userId}:$operationId:${path.toSortedMap()}:${query.filterKeys { it!="cursor" }.toSortedMap()}")
    val cursor = query["cursor"]?.let { json.parseToJsonElement(unseal(it)).jsonObject }
    val snapshot = cursor?.string("snapshot") ?: now.toString()
    val expires = cursor?.string("expires") ?: now.plusSeconds(900).toString()
    if (cursor != null && (cursor.string("binding") != binding || Instant.parse(expires).isBefore(now))) fail(410,"CURSOR_EXPIRED","Snapshot expired")
    val (where,initial) = auditWhere(table,emptyMap())
    val values = initial.toMutableList()
    val idColumn = if (table=="api_requests") "request_id" else "id"
    val sql = StringBuilder("SELECT $idColumn,created_at,payload FROM $table WHERE $where AND created_at <= ?")
    values += snapshot
    query["since"]?.let { sql.append(" AND created_at >= ?"); values += it }
    query["until"]?.let { sql.append(" AND created_at < ?"); values += it }
    if (cursor != null) { sql.append(" AND (created_at < ? OR (created_at = ? AND $idColumn > ?))"); values.addAll(listOf(cursor.string("at")!!,cursor.string("at")!!,cursor.string("last")!!)) }
    if (tx.postgres) mapOf("category" to "category","action" to "action","outcome" to "outcome","severity" to "severity","operationId" to "operationId").forEach { (queryKey,payloadKey) ->
        query[queryKey]?.let { sql.append(" AND payload::jsonb ->> ? = ?"); values.addAll(listOf(payloadKey,it)) }
    }
    sql.append(" ORDER BY created_at DESC,$idColumn LIMIT ?"); values += limit+1
    val rows = tx.connection.prepareStatement(sql.toString()).use { statement ->
        values.forEachIndexed { index,value -> statement.setObject(index+1,value) }
        statement.executeQuery().use { rs -> buildList { while(rs.next()) add(Triple(rs.getString(1),rs.getString(2),json.parseToJsonElement(rs.getString(3)))) } }
    }
    val last = rows.take(limit).lastOrNull()
    return obj("items" to rows.take(limit).map { it.third },"page" to obj("snapshotAt" to snapshot,"snapshotExpiresAt" to expires,
        "nextCursor" to if(rows.size>limit && last!=null) seal(obj("binding" to binding,"snapshot" to snapshot,"expires" to expires,"at" to last.second,"last" to last.first).toString()) else null))
}

private fun V1Context.verifyChain(): JsonObject {
    val since = query["since"] ?: Instant.EPOCH.toString()
    val until = query["until"] ?: now.toString()
    val previous = mutableMapOf<String,String>(); var checked=0; var broken:String?=null
    tx.connection.prepareStatement("SELECT id,previous_hash,hash,payload,created_at,chain_scope FROM audit_log WHERE tenant_id=? AND brand_id=? ORDER BY sequence").use {
        it.setString(1,tenantId);it.setString(2,brandId)
        it.executeQuery().use { rows -> while(rows.next()) {
            val entry=json.parseToJsonElement(rows.getString("payload")).jsonObject
            val scope = rows.getString("chain_scope") ?: "brand"
            val prior = previous[scope].orEmpty()
            val inRange=Instant.parse(rows.getString("created_at")).let { at -> !at.isBefore(Instant.parse(since)) && at.isBefore(Instant.parse(until)) }
            if(inRange) {
                checked++
                if(broken==null && (rows.getString("previous_hash")!=prior || Secrets.hash(prior+JsonObject(entry-"hash").toString())!=rows.getString("hash"))) broken=rows.getString("id")
            }
            previous=rows.getString("hash")
        } }
    }
    return obj("since" to since,"until" to until,"entriesChecked" to checked,"intact" to (broken==null),"firstBrokenEntryId" to broken,"checkedAt" to now)
}

private fun V1Context.acceptClientEvent(index:Int,event:JsonObject):JsonObject {
    fun result(status:String,reason:String?=null,id:String?=null)=obj("index" to index,"result" to status,"reason" to reason,"auditEntryId" to id)
    val type=event.string("type")!!
    val allowed=if(principal?.deviceId!=null) type.startsWith("device.") else if(type.startsWith("web.")) principal?.staff==true else type.startsWith("app.")
    if(!allowed) return result("rejected","type_not_allowed_for_actor")
    if(kotlin.math.abs(java.time.Duration.between(Instant.parse(event.string("occurredAt")),now).seconds)>86400) return result("rejected","clock_skew")
    val details=event["details"] as? JsonObject ?: obj()
    if(details.toString().toByteArray().size>4096 || details.size>20) return result("rejected","payload_too_large")
    if(redact(details)!=details || Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+|\\b[0-9]{11}\\b").containsMatchIn(details.toString())) return result("rejected","pii_detected")
    val contextId=event.string("contextId")
    val member=contextId?.let { store.find("membership",it) ?: store.find("staff_assignment",it) }
    if(contextId!=null && (member?.ownerId!=principal?.userId || member?.data?.string("status")!="active")) return result("rejected","context_not_owned")
    val target=(event["target"] as? JsonObject)?.let { ref -> store.find(ref.string("type")!!,ref.string("id")!!) }
    if(event["target"] is JsonObject && target==null) return result("rejected","target_not_accessible")
    if(target!=null) {
        val expected=when {
            type.contains("camera_view") -> "camera"
            type.contains("recording_played") -> "recording"
            type.contains("pickup_code") -> "parcel"
            type.contains("access_qr") -> "access_invite"
            type.contains("export_download") -> "export"
            else -> null
        }
        if(expected!=null && target.kind!="v1_$expected") return result("rejected","target_mismatch")
        val scopes=if(principal?.deviceId!=null) listOf(store.get("device",principal.deviceId).locationId) else store.list("membership",ownerId=principal?.userId,filters=mapOf("status" to "active")).map { it.locationId } + store.list("staff_assignment",ownerId=principal?.userId,filters=mapOf("status" to "active")).map { it.locationId }
        if(target.ownerId!=principal?.userId && (target.locationId==null || target.locationId !in scopes)) return result("rejected","target_not_accessible")
        if(member!=null && member.locationId!=target.locationId) return result("rejected","target_not_accessible")
    }
    val fingerprint=hash("${principal?.userId}:${principal?.deviceId}:$event")
    store.find("client_event",fingerprint)?.let { return result("duplicate",id=it.data.string("entryId")) }
    val scoped=V1Context(tx,operationId,tenantId,brandId,requestId,principal=principal,locationId=member?.locationId ?: target?.locationId)
    val entry=appendAudit(scoped,type,target,details=obj("reportedEvent" to event,"observedByServer" to false))
    store.create("client_event",obj("entryId" to entry["id"]),scoped.locationId,principal?.userId,id=fingerprint)
    return result("accepted",id=entry.string("id"))
}
