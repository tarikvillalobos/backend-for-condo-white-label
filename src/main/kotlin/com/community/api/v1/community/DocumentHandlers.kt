package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*

private fun V1Context.documentVersion(row: Record): JsonObject {
    val upload = store.get("upload", row.data.text("fileKey")!!)
    return view("DocumentVersion", row, obj("fileUrl" to fileUrl(upload.id), "contentType" to upload.data["contentType"],
        "sizeBytes" to (upload.data["size"] ?: upload.data["sizeBytes"]), "sha256" to (upload.data["checksum"] ?: upload.data["sha256"]),
        "notes" to row.data["notes"], "publishedAt" to row.createdAt, "publishedByName" to personName(row.ownerId)))
}
private fun V1Context.document(row: Record): JsonObject {
    val revision = row.data.number("currentRevision", 1)
    val current = store.list("document_version", locationId, filters = mapOf("documentId" to row.id, "revision" to revision.toString())).single()
    val acknowledged = store.list("receipt", locationId, filters = mapOf("resourceId" to row.id, "membershipId" to (membershipId ?: "")))
        .maxOfOrNull { it.data.number("revision") }
    return view("Document", row, obj("description" to row.data["description"],
        "targetNodes" to JsonArray(row.data.array("targetNodeIds").map { node(it.jsonPrimitive.content) }),
        "currentRevision" to revision, "current" to documentVersion(current), "acknowledgedRevision" to acknowledged,
        "archived" to row.data.flag("archived")))
}
private fun V1Context.documentVisible(row: Record) = membershipId == null || (!row.data.flag("archived") && visible(row.data))
private fun V1Context.documentRecord(): Record = store.get("document", id("documentId"), locationId).also {
    if (!documentVisible(it)) fail(404, "NOT_FOUND", "Documento não encontrado")
}
private fun V1Context.requireDocumentUpload(key: String) {
    val upload = store.get("upload", key)
    if (upload.ownerId != userId && principal?.permissions?.contains("*") != true) fail(403, "UPLOAD_NOT_OWNED", "Arquivo pertence a outro usuário")
    val allowed = setOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "image/png", "image/jpeg", "image/webp")
    if (upload.data.text("contentType") !in allowed) fail(422, "DOCUMENT_CONTENT_TYPE", "Formato de documento não permitido")
    fileUrl(key)
}
internal fun documentHandlers(): Map<String, V1Handler> = mapOf(
    "listDocuments" to V1Handler { c -> c.listResponse("document") { if (c.documentVisible(it)) c.document(it) else JsonNull } },
    "adminListDocuments" to V1Handler { c -> c.listResponse("document") { c.document(it) } },
    "getDocument" to V1Handler { c -> V1Response(c.document(c.documentRecord())) },
    "acknowledgeDocument" to V1Handler { c ->
        val row = c.documentRecord()
        c.acknowledge(row, row.data.number("currentRevision", 1))
