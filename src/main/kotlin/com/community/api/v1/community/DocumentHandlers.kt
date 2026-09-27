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
