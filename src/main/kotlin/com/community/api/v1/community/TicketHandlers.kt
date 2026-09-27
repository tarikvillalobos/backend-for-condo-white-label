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
