package com.community.api.v1.community

import com.community.api.core.ApiException
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.*

class CommunityDocumentsAndReportsTest {
    @Test fun `document revisions invalidate acknowledgments only when requested`(): Unit = CommunityFixture().use { f ->
        val file = f.file()
        val document = f.run("adminCreateDocument", obj("title" to "Regulamento", "category" to "rules", "requiresAcknowledgment" to true,
            "fileKey" to file.id, "targetNodeIds" to JsonArray(listOf(JsonPrimitive(f.unit)))), staff = true)
        val ids = mapOf("documentId" to document.id())
        assertEquals(404, assertFailsWith<ApiException> { f.run("getDocument", ids = ids, other = true) }.status)
        f.run("acknowledgeDocument", ids = ids)
        val second = f.run("adminPublishDocumentVersion", obj("fileKey" to file.id, "requireNewAcknowledgment" to false), ids, staff = true)
        assertEquals(2, second.body.jsonObject["currentRevision"]!!.jsonPrimitive.int)
        assertEquals(0, f.run("adminListDocumentAcknowledgments", ids = ids, staff = true, query = mapOf("pending" to "true")).items().size)
        f.run("adminPublishDocumentVersion", obj("fileKey" to file.id, "requireNewAcknowledgment" to true), ids, staff = true)
        assertEquals(1, f.run("adminListDocumentAcknowledgments", ids = ids, staff = true, query = mapOf("pending" to "true")).items().size)
        f.run("acknowledgeDocument", ids = ids)
        assertEquals(3, f.run("adminListDocumentVersions", ids = ids, staff = true).items().size)
        f.run("adminArchiveDocument", ids = ids, staff = true)
        assertTrue(f.run("listDocuments").items().isEmpty())
    }
    @Test fun `camera policies and explicit grants control access before provider lookup`(): Unit = CommunityFixture().use { f ->
        val camera = f.run("adminCreateCamera", obj("name" to "Hall", "area" to "hall", "provider" to "http", "providerRef" to "camera-1",
            "liveAllowed" to true, "recordingsAllowed" to false), staff = true)
        val ids = mapOf("cameraId" to camera.id())
        f.run("adminSetCameraPolicies", obj("policies" to JsonArray(listOf(obj("role" to null, "scopeNodeId" to f.unit, "allowLive" to true, "allowRecordings" to false)))), ids, staff = true)
        assertEquals(1, f.run("listCameras").items().size)
        assertEquals(0, f.run("listCameras", other = true).items().size)
        assertEquals(403, assertFailsWith<ApiException> { f.run("createStreamSession", obj("protocol" to "hls"), ids, other = true) }.status)
        val grantIds = ids + ("membershipId" to f.otherMember)
        f.run("adminSetCameraGrant", obj("membershipId" to f.otherMember, "allowLive" to true, "allowRecordings" to false), grantIds, staff = true)
        assertEquals(1, f.run("listCameras", other = true).items().size)
        if (System.getenv("CAMERA_PROVIDER_BASE_URL") == null)
            assertEquals(501, assertFailsWith<ApiException> { f.run("createStreamSession", obj("protocol" to "hls"), ids) }.status)
