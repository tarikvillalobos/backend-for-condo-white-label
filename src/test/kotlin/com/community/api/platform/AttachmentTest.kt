package com.community.api.platform

import com.community.api.core.*
import com.community.api.identity.issueSession
import com.community.api.module
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.*

class AttachmentTest {
    private val pdf = Base64.getEncoder().encodeToString("%PDF-1.7\nexample\n%%EOF".toByteArray())

    @Test
    fun `uploads validate filename media signature and decoded size`() {
        assertFailsWith<ApiException> { validateAttachment(AttachmentInput("../secret.pdf", "application/pdf", pdf)) }
        assertFailsWith<ApiException> { validateAttachment(AttachmentInput("image.png", "image/png", pdf)) }
        assertFailsWith<ApiException> { validateAttachment(AttachmentInput("file.pdf", "application/pdf", "?")) }
        assertEquals(413, assertFailsWith<ApiException> { validateAttachment(AttachmentInput("file.pdf", "application/pdf", "a".repeat(2_796_205))) }.status)
    }

    @Test
    fun `private file downloads require ownership and return attachment headers`() = testApplication {
        val f = PlatformFixture()
        val residentToken = f.db.tx { tx ->
            tx.create("membership", f.tenant, f.location, f.resident, body(Membership(f.resident, f.location)))
            tx.issueSession(tx.requireRecord("account", f.resident, f.tenant), "test").accessToken
        }
        application { module(f.db) }
        val created = client.post("/api/v1/locations/${f.location}/attachments") {
            bearerAuth(f.token); contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AttachmentInput("example.pdf", "application/pdf", pdf)))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val path = json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("downloadPath").jsonPrimitive.content
        val denied = client.get(path) { bearerAuth(residentToken) }
