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
