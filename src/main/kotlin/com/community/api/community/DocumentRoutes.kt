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
    if (mediaType !in setOf("application/pdf", "image/jpeg", "image/png", "text/plain",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) badRequest("Unsupported document media type")
    return copy(title = text(title, "title", 160), description = text(description, "description", 2000), url = url(url))
}
internal fun Route.documentRoutes(db: Database) {
    route("/documents") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "documents.read", "documents")
                tx.list("document", ctx.tenantId, ctx.locationId).filter {
                    val item = it.decode<CommunityDocument>()
                    (ctx.can("documents.manage") || !item.archived) && tx.audience(ctx, item.content.unitId, "documents.manage")
                }
            })
        }
        post {
            val input = call.receive<DocumentInput>().validated()
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "documents.manage", "documents")
                tx.requireUnit(ctx, input.unitId, "documents.manage")
                val doc = tx.saved(ctx, "document", body(CommunityDocument(input)))
                tx.saved(ctx, "document_version", body(DocumentVersion(doc.id, 1, input)))
                doc
            })
        }
        post("/{id}/versions") {
            val input = call.receive<DocumentInput>().validated()
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "documents.manage", "documents")
                val row = tx.record(ctx, "document", call.resourceId())
                tx.requireUnit(ctx, input.unitId, "documents.manage")
                val current = row.decode<CommunityDocument>()
                if (current.archived) conflict("Document is archived")
                val revised = current.copy(content = input, revision = current.revision + 1)
                tx.saved(ctx, "document_version", body(DocumentVersion(row.id, revised.revision, input)))
                tx.changed(ctx, row, body(revised), "document.revised")
            })
        }
        get("/{id}/versions") {
            call.respondPage(db.query { tx ->
