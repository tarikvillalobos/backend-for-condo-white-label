package com.community.api.v1

import com.community.api.core.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

private val fileDirectory: Path get() = Path.of(System.getenv("UPLOAD_DIRECTORY") ?: "data/uploads").toAbsolutePath()
private val publicBase: String get() = (System.getenv("PUBLIC_BASE_URL") ?: "http://localhost:8080").trimEnd('/')

private fun V1Context.fileTicket(id: String, action: String, seconds: Long = 900): String = seal(obj(
    "tenant" to tenantId,"brand" to brandId,"id" to id,"action" to action,"owner" to principal?.userId,
    "expires" to now.plusSeconds(seconds)).toString())

fun signedFileUrl(c: V1Context, fileKey: String): String {
    val file = c.store.get("upload",fileKey)
    if (file.data.string("status") != "complete") c.fail(409,"UPLOAD_INCOMPLETE","Complete the upload first")
    return "$publicBase/v1/files/$fileKey?ticket=${c.fileTicket(fileKey,"download")}" 
}

fun writePrivateFile(c: V1Context, filename: String, contentType: String, bytes: ByteArray): Record {
    require(bytes.size <= 50 * 1024 * 1024) { "Generated file exceeds 50 MiB; use a narrower period" }
    val id = UUID.randomUUID().toString()
    persistFile(id,bytes)
    return c.store.create("upload",obj("filename" to filename,"contentType" to contentType,"size" to bytes.size,
        "sizeBytes" to bytes.size,"checksum" to java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        "status" to "complete","purpose" to "export","completedAt" to c.now),ownerId=c.principal?.userId,id=id)
}

private fun persistFile(id: String, bytes: ByteArray) {
    require(runCatching { UUID.fromString(id) }.isSuccess)
    Files.createDirectories(fileDirectory)
    val temporary = Files.createTempFile(fileDirectory,"upload-",".tmp")
    try {
        Files.write(temporary,bytes)
        Files.move(temporary,fileDirectory.resolve(id),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temporary) }
}

fun fileHandlers(): Map<String,V1Handler> = mapOf("createUpload" to V1Handler { c ->
    val contentType = c.input.string("contentType")!!
    val size = c.input["sizeBytes"]!!.jsonPrimitive.long
    val record = c.store.create("upload",obj("filename" to "upload","contentType" to contentType,"size" to size,"sizeBytes" to size,
        "purpose" to c.input["purpose"],"status" to "pending","expiresAt" to c.now.plusSeconds(900)),ownerId=c.userId)
    V1Response(obj("fileKey" to record.id,"uploadUrl" to "$publicBase/v1/files/${record.id}?ticket=${c.fileTicket(record.id,"upload")}",
        "method" to "PUT","headers" to obj("Content-Type" to contentType),"expiresAt" to c.now.plusSeconds(900)),201)
})

fun Route.fileRoutes(db: Database) {
    route("/v1/files/{fileId}") {
        put {
            val ticket = fileClaims(call.parameters["fileId"]!!,call.request.queryParameters["ticket"],"upload")
            val bytes = call.receive<ByteArray>()
            db.scopedQuery("file:${ticket.string("id")}") { tx ->
                val c = fileContext(tx,ticket,"uploadFile")
                val file = c.store.get("upload",ticket.string("id")!!)
                if (file.data.string("status") != "pending") c.fail(409,"UPLOAD_COMPLETE","Upload ticket already consumed")
                if (bytes.size.toLong() != file.data["sizeBytes"]!!.jsonPrimitive.long) c.fail(422,"VALIDATION_ERROR","File size does not match upload ticket")
                val type = file.data.string("contentType")!!
                if (call.request.contentType().withoutParameters().toString() != type) c.fail(415,"VALIDATION_ERROR","Content type does not match upload ticket")
                validateFile(type,bytes)
                persistFile(file.id,bytes)
                tx.requestMetadata(c.requestId,c.operationId,c.principal)
                val checksum = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val updated = c.store.update(file,JsonObject(file.data+obj("status" to "complete","completedAt" to c.now,"checksum" to checksum)))
                c.audit("file.uploaded",updated)
            }
            call.respond(HttpStatusCode.NoContent)
        }
        get {
            val ticket = fileClaims(call.parameters["fileId"]!!,call.request.queryParameters["ticket"],"download")
            val file = db.scopedQuery(null) { tx ->
                val c = fileContext(tx,ticket,"downloadFile")
                val record = c.store.get("upload",ticket.string("id")!!)
                if (record.data.string("status") != "complete") c.fail(404,"RESOURCE_NOT_FOUND","File not found")
                c.audit("file.downloaded",record)
                record
            }
            val local = fileDirectory.resolve(file.id)
            if (!Files.isRegularFile(local)) throw ApiException(404,"RESOURCE_NOT_FOUND","File not found")
            call.response.headers.append(HttpHeaders.ContentDisposition,"attachment")
            call.respondFile(local.toFile())
        }
    }
}

private fun fileClaims(id: String, encrypted: String?, action: String): JsonObject {
    if (encrypted == null || runCatching { UUID.fromString(id) }.isFailure) throw ApiException(401,"ACCESS_DENIED","Signed file ticket required")
    val claims = runCatching { json.parseToJsonElement(Secrets.unseal(encrypted)).jsonObject }.getOrElse { throw ApiException(401,"ACCESS_DENIED","Invalid file ticket") }
    if (claims.string("id") != id || claims.string("action") != action || Instant.parse(claims.string("expires")).isBefore(Instant.now())) throw ApiException(401,"ACCESS_DENIED","Expired or invalid file ticket")
    return claims
}
private fun fileContext(tx: Tx, claims: JsonObject, operation: String): V1Context = V1Context(tx,operation,
    claims.string("tenant")!!,claims.string("brand")!!,UUID.randomUUID().toString(),principal=V1Principal(userId=claims.string("owner")))

private fun validateFile(type: String, bytes: ByteArray) {
    val valid = when(type) {
        "image/png" -> bytes.take(8) == listOf(137,80,78,71,13,10,26,10).map(Int::toByte)
        "image/jpeg" -> bytes.size > 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()
        "image/webp" -> bytes.size >= 12 && String(bytes,0,4) == "RIFF" && String(bytes,8,4) == "WEBP"
        "application/pdf" -> bytes.size >= 5 && String(bytes,0,5) == "%PDF-"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> bytes.size >= 4 && bytes[0] == 80.toByte() && bytes[1] == 75.toByte()
        "text/csv" -> bytes.none { it == 0.toByte() }
        else -> false
    }
    if (!valid) throw ApiException(422,"VALIDATION_ERROR","File content does not match declared type")
}

fun V1Context.enqueueMail(to: String, subject: String, text: String) {
    val data = obj("type" to "v1_message","email" to to,"credential" to "sealed:${seal(obj("subject" to subject,"text" to text).toString())}",
        "expiresAt" to now.plusSeconds(86400),"attempts" to 0,"nextAttemptAt" to null,"leaseId" to null,"leaseUntil" to null,"lastFailure" to null,"status" to "pending")
    tx.create("auth_delivery",tenantId,data=data)
}
