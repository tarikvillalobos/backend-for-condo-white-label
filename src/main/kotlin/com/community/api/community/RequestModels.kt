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
