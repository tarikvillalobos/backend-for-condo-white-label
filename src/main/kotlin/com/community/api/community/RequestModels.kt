package com.community.api.community

import com.community.api.core.*
import kotlinx.serialization.Serializable

@Serializable
data class RequestInput(val title: String, val description: String, val category: String = "request",
    val priority: String = "normal", val attachments: List<String> = emptyList())
@Serializable
data class ResidentRequest(val content: RequestInput, val status: String = "open", val assignedTo: String? = null, val dueAt: String? = null)
@Serializable
data class RequestAssignment(val userId: String, val dueAt: String? = null)
@Serializable
data class RequestTransition(val status: String, val reason: String)
@Serializable
data class RequestCommentInput(val message: String, val internal: Boolean = false, val attachments: List<String> = emptyList())
@Serializable
data class RequestComment(val requestId: String, val message: String, val internal: Boolean, val attachments: List<String>)

internal fun RequestInput.validated(): RequestInput {
    if (priority !in setOf("normal", "high", "urgent")) badRequest("Invalid priority")
    if (category !in setOf("request", "complaint", "incident", "maintenance", "support")) badRequest("Invalid category")
    if (attachments.size > 10) badRequest("At most 10 attachments are allowed")
    return copy(title = text(title, "title", 160), description = text(description, "description", 10000), attachments = attachments.map(::url))
}
internal fun validateRequestTransition(previous: String, next: String, staff: Boolean) {
    val transitions = mapOf("open" to setOf("in_progress", "cancelled"), "in_progress" to setOf("resolved", "open", "cancelled"),
        "resolved" to setOf("closed", "open"), "closed" to setOf("open"), "cancelled" to emptySet())
    if (next !in transitions.getOrDefault(previous, emptySet())) conflict("Invalid request status transition")
    if (!staff && !((previous == "open" && next == "cancelled") || (previous in setOf("resolved", "closed") && next == "open"))) forbidden()
}
