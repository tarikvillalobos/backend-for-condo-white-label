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
