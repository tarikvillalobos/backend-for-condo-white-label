package com.community.api.community

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class DocumentInput(val title: String, val description: String, val url: String, val mediaType: String,
    val unitId: String? = null, val acknowledgmentRequired: Boolean = false)
@Serializable
data class CommunityDocument(val content: DocumentInput, val revision: Int = 1, val archived: Boolean = false)
@Serializable
data class DocumentVersion(val documentId: String, val revision: Int, val content: DocumentInput)
@Serializable
data class DocumentAcknowledgment(val documentId: String, val revision: Int)

private fun DocumentInput.validated(): DocumentInput {
