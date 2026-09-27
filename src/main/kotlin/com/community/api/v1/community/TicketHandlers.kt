package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.util.UUID

internal fun V1Context.ticket(row: Record, admin: Boolean = false): JsonObject {
    val comments = store.list("ticket_comment", locationId, filters = mapOf("ticketId" to row.id))
        .filter { admin || !it.data.flag("internal") }.map { view(if (admin) "AdminComment" else "Comment", it) }
    val schema = if (admin) "AdminTicket" else if (row.data.text("kind") == "occurrence") "Occurrence" else "ServiceRequest"
    return view(schema, row, obj("comments" to JsonArray(comments), "resolvedAt" to row.data["resolvedAt"],
        "location" to row.data["location"], "node" to node(row.data.text("nodeId")),
        "authorName" to if (row.data.flag("anonymous")) null else personName(row.ownerId),
        "parcelId" to row.data["parcelId"], "category" to row.data["category"], "title" to row.data["title"],
        "assignedToName" to row.data.text("assignedToUserId")?.let { personName(it) },
        "attachmentUrls" to row.data.array("attachmentUrls"), "priority" to (row.data.text("priority") ?: "normal"),
        "dueAt" to row.data["dueAt"], "escalations" to row.data.array("escalations")))
}
private fun V1Context.newTicket(kind: String): V1Response {
    val reference = "${if (kind == "occurrence") "OC" else "SR"}-${UUID.randomUUID().toString().take(8).uppercase()}"
    val row = save("ticket", input.merge(obj("kind" to kind, "reference" to reference,
        "status" to "received", "nodeId" to unitId, "priority" to (input.text("priority") ?: "normal"))))
    return V1Response(ticket(row), 201)
}
private fun V1Context.ticketBy(key: String, kind: String? = null): Record = record("ticket", key).also {
    if (kind != null && it.data.text("kind") != kind) fail(404, "NOT_FOUND", "Chamado não encontrado")
}
internal fun V1Context.commentTicket(row: Record, body: String, internal: Boolean, admin: Boolean): JsonObject {
    if (row.data.text("status") in setOf("closed", "rejected", "dismissed")) fail(409, "TICKET_CLOSED", "Chamado encerrado")
    val comment = save("ticket_comment", obj("ticketId" to row.id, "body" to body, "internal" to internal,
        "authorName" to personName(), "authorRole" to if (admin) "staff" else "resident"))
    change(row, obj("lastCommentAt" to now()), "ticket.commented")
    if (admin && !internal) row.data.text("membershipId")?.let { store.find("membership", it, locationId) }
        ?.let { notifyMember(it, if (row.data.text("kind") == "occurrence") "occurrence" else "request", row.id, "Resposta ao chamado", body) }
    return view(if (admin) "AdminComment" else "Comment", comment)
}
private fun V1Context.updateTicketState(row: Record): V1Response {
    val previous = row.data.text("status")!!
    val requested = input.text("status")!!
    val transitions = when (row.data.text("kind")) {
        "occurrence" -> occurrenceTransitions
        "support_issue" -> mapOf("received" to setOf("in_progress", "resolved", "closed"), "in_progress" to setOf("resolved", "closed"), "resolved" to setOf("closed", "in_progress"))
        else -> requestTransitions
    }
    if (previous != requested && requested !in transitions[previous].orEmpty()) fail(409, "TICKET_INVALID_TRANSITION", "Transição inválida do chamado")
    requireStaffUser(input.text("assignedToUserId"))
    var data = input.merge(obj("resolvedAt" to if (requested == "resolved") now() else row.data["resolvedAt"]))
    if ("assignedToUserId" !in input) data = JsonObject(data - "assignedToUserId")
    val updated = change(row, data, "ticket.status_changed")
    input.text("comment")?.takeIf { it.isNotBlank() }?.let { commentTicket(updated, it, false, true) }
    return V1Response(status = 204)
}
internal fun ticketHandlers(): Map<String, V1Handler> = mapOf(
    "listServiceRequests" to V1Handler { c -> c.listResponse("ticket", true, mapOf("kind" to "service_request")) { c.ticket(it) } },
    "createServiceRequest" to V1Handler { c -> c.newTicket("service_request") },
    "getServiceRequest" to V1Handler { c -> V1Response(c.ticket(c.ticketBy("requestId", "service_request"))) },
    "commentServiceRequest" to V1Handler { c -> V1Response(c.commentTicket(c.ticketBy("requestId", "service_request"), c.input.text("body")!!, false, false), 201) },
    "listOccurrences" to V1Handler { c -> c.listResponse("ticket", true, mapOf("kind" to "occurrence")) { c.ticket(it) } },
    "createOccurrence" to V1Handler { c ->
        if (timestamp(c.input.text("occurredAt")!!).isAfter(c.now.plusSeconds(60))) c.fail(422, "INVALID_OCCURRENCE_TIME", "Ocorrência no futuro")
        c.newTicket("occurrence")
    },
    "getOccurrence" to V1Handler { c -> V1Response(c.ticket(c.ticketBy("occurrenceId", "occurrence"))) },
    "adminListTickets" to V1Handler { c -> c.listResponse("ticket") { c.ticket(it, true) } },
    "adminGetTicket" to V1Handler { c -> V1Response(c.ticket(c.ticketBy("ticketId"), true)) },
    "updateTicket" to V1Handler { c -> c.updateTicketState(c.ticketBy("ticketId")) },
    "adminCommentTicket" to V1Handler { c -> V1Response(c.commentTicket(c.ticketBy("ticketId"), c.input.text("body")!!, c.input.flag("internal"), true), 201) },
    "adminAssignTicket" to V1Handler { c ->
        c.requireStaffUser(c.input.text("userId"))
        val row = c.change(c.ticketBy("ticketId"), obj("assignedToUserId" to c.input["userId"], "dueAt" to c.input["dueAt"]), "ticket.assigned")
        V1Response(c.ticket(row, true))
    },
    "adminEscalateTicket" to V1Handler { c -> c.escalateTicket(c.ticketBy("ticketId")) },
    "adminCreateWorkOrderFromTicket" to V1Handler { c ->
        val ticket = c.ticketBy("ticketId")
        val data = c.input.merge(obj("ticketId" to ticket.id, "title" to (c.input.text("title") ?: ticket.data.text("title") ?: ticket.data.text("reference")),
            "description" to ticket.data["description"], "nodeId" to ticket.data["nodeId"], "priority" to (ticket.data.text("priority") ?: "normal")))
        V1Response(c.workOrder(c.createWorkOrder(data)), 201)
    },
