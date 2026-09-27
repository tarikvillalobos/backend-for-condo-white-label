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
