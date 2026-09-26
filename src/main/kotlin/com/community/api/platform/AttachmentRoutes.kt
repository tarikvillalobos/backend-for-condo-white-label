package com.community.api.platform

import com.community.api.core.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Base64

@Serializable
data class AttachmentInput(val filename: String, val contentType: String, val contentBase64: String, val visibility: String = "private")

@Serializable
data class AttachmentData(val filename: String, val contentType: String, val contentBase64: String, val visibility: String, val size: Int, val sha256: String)

@Serializable
data class AttachmentView(val id: String, val filename: String, val contentType: String, val size: Int, val sha256: String, val downloadPath: String)

fun validateAttachment(input: AttachmentInput): ByteArray {
    if (!Regex("[A-Za-z0-9][A-Za-z0-9._ -]{0,119}").matches(input.filename)) badRequest("Invalid filename")
    if (input.visibility !in setOf("private", "location")) badRequest("Invalid visibility")
    if (input.contentBase64.length > 2_796_204) throw ApiException(413, "payload_too_large", "Files must not exceed 2 MiB")
    val bytes = runCatching { Base64.getDecoder().decode(input.contentBase64) }.getOrElse { badRequest("Invalid base64 data") }
    if (bytes.isEmpty() || bytes.size > 2 * 1024 * 1024) throw ApiException(413, "payload_too_large", "Files must contain 1 byte to 2 MiB")
    val valid = when (input.contentType) {
        "image/png" -> bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) && input.filename.endsWith(".png", true)
        "image/jpeg" -> bytes.size >= 4 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte() && (input.filename.endsWith(".jpg", true) || input.filename.endsWith(".jpeg", true))
        "application/pdf" -> bytes.take(5).toByteArray().decodeToString() == "%PDF-" && input.filename.endsWith(".pdf", true)
        else -> false
    }
    if (!valid) badRequest("Supported files are PNG, JPEG, and PDF with matching signatures and extensions")
    return bytes
}

fun Route.attachmentRoutes(db: Database) {
    route("/api/v1/locations/{locationId}/attachments") {
        post {
            val input = call.receive<AttachmentInput>()
            val bytes = validateAttachment(input)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.parameters["locationId"]!!, "attachments.create", "documents")
                if (input.visibility == "location" && !ctx.can("documents.manage")) forbidden()
                if (tx.list("attachment", ctx.tenantId, ownerId = ctx.userId).size >= 200) conflict("Attachment quota reached")
                val record = tx.create("attachment", ctx.tenantId, ctx.locationId, ctx.userId, body(AttachmentData(input.filename, input.contentType, input.contentBase64, input.visibility, bytes.size, digest)))
                tx.audit(ctx, "attachment.created", record.id)
                AttachmentView(record.id, input.filename, input.contentType, bytes.size, digest, "/api/v1/locations/${ctx.locationId}/attachments/${record.id}")
            })
        }
        get("/{id}") {
            val data = db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.parameters["locationId"]!!, setOf("attachments.read.own", "attachments.read.all", "documents.read"), "documents")
                val record = tx.requireRecord("attachment", call.parameters["id"]!!, ctx.tenantId, ctx.locationId)
                val data = record.decode<AttachmentData>()
                if (record.ownerId != ctx.userId && !ctx.can("attachments.read.all") && !(data.visibility == "location" && ctx.can("documents.read"))) notFound()
                tx.audit(ctx, "attachment.downloaded", record.id)
                data
            }
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"${data.filename}\"")
            call.respondBytes(Base64.getDecoder().decode(data.contentBase64), ContentType.parse(data.contentType))
        }
        delete("/{id}") {
            db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.parameters["locationId"]!!, setOf("attachments.create", "documents.manage"), "documents")
                val record = tx.requireRecord("attachment", call.parameters["id"]!!, ctx.tenantId, ctx.locationId)
                if (record.ownerId != ctx.userId && !ctx.can("documents.manage")) notFound()
                tx.delete(record)
                tx.audit(ctx, "attachment.deleted", record.id)
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
