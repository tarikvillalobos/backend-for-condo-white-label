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
