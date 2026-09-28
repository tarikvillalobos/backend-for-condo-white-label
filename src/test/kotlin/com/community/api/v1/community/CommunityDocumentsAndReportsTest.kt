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
