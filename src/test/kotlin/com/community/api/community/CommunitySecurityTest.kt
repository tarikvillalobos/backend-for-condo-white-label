package com.community.api.community

import com.community.api.core.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import kotlin.test.*

class CommunitySecurityTest {
    @Test
    fun `request ownership and internal notes are enforced`() = testApplication {
        CommunityFixture().use { f ->
            f.install(this)
            val created = client.post(f.path("requests")) {
                bearerAuth(f.resident.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RequestInput("Broken light", "Hallway light failed")))
            }
            assertEquals(HttpStatusCode.Created, created.status)
            val id = json.decodeFromString<Record>(created.bodyAsText()).id
            assertEquals(HttpStatusCode.Forbidden, client.get(f.path("requests/$id")) { bearerAuth(f.other.token) }.status)
            val internal = client.post(f.path("requests/$id/comments")) {
                bearerAuth(f.manager.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RequestCommentInput("Staff investigation only", internal = true)))
            }
            assertEquals(HttpStatusCode.Created, internal.status)
            val notes = client.get(f.path("requests/$id/comments")) { bearerAuth(f.resident.token) }
            assertFalse(notes.bodyAsText().contains("Staff investigation"))
            assertEquals(HttpStatusCode.Forbidden, client.post(f.path("requests/$id/comments")) {
                bearerAuth(f.resident.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RequestCommentInput("Private", internal = true)))
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post(f.path("requests/$id/status")) {
                bearerAuth(f.resident.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RequestTransition("in_progress", "Taking over")))
            }.status)
            assertEquals(HttpStatusCode.OK, client.post(f.path("requests/$id/status")) {
                bearerAuth(f.manager.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RequestTransition("in_progress", "Assigned technician")))
            }.status)
        }
    }

    @Test
    fun `unit association cannot be spoofed and public pet notices omit private data`() = testApplication {
        CommunityFixture().use { f ->
            f.install(this)
            assertEquals(HttpStatusCode.Forbidden, client.post(f.path("pets")) {
                bearerAuth(f.resident.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(PetInput("Luna", "cat", f.otherUnit)))
            }.status)
            val created = client.post(f.path("pets")) {
                bearerAuth(f.resident.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(PetInput("Luna", "cat", f.unit, "private-chip-id", vaccinationUrls = listOf("https://example.test/private.pdf"))))
            }
            val id = json.decodeFromString<Record>(created.bodyAsText()).id
            client.post(f.path("lost-pets")) {
                bearerAuth(f.resident.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(LostPetInput(id, "Missing since morning", "Garden")))
