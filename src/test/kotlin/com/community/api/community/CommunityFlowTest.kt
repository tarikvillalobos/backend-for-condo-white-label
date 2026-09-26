package com.community.api.community

import com.community.api.core.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.*

class CommunityFlowTest {
    @Test
    fun `visitor admission requires staff and single use survives checkout`() = testApplication {
        CommunityFixture().use { f ->
            f.install(this)
            val now = Instant.now()
            val response = client.post(f.path("visitors")) {
                bearerAuth(f.resident.token); contentType(ContentType.Application.Json)
                header("Idempotency-Key", "visitor-request-001")
                setBody(json.encodeToString(VisitorInput("Guest", "Visit", now.minusSeconds(60).toString(), now.plusSeconds(3600).toString(), f.unit)))
            }
            assertEquals(HttpStatusCode.Created, response.status)
            val invitation = json.decodeFromString<VisitorCreated>(response.bodyAsText())
            val id = invitation.invitation.id
            val code = invitation.admissionCode
            assertEquals(HttpStatusCode.Conflict, client.post(f.path("visitors")) {
                bearerAuth(f.resident.token); contentType(ContentType.Application.Json)
                header("Idempotency-Key", "visitor-request-001")
                setBody(json.encodeToString(VisitorInput("Guest", "Visit", now.minusSeconds(60).toString(), now.plusSeconds(3600).toString(), f.unit)))
            }.status)
            assertEquals(1, f.db.tx { it.list("visitor", f.tenant, f.location).size })
            val list = client.get(f.path("visitors")) { bearerAuth(f.resident.token) }.bodyAsText()
            assertFalse(list.contains(code))
            assertFalse(list.contains("credentialHash"))
            assertEquals(HttpStatusCode.Forbidden, client.post(f.path("visitors/$id/check-in")) {
                bearerAuth(f.resident.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(VisitorCheckIn(code)))
            }.status)
            assertEquals(HttpStatusCode.OK, client.post(f.path("visitors/$id/check-in")) {
                bearerAuth(f.manager.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(VisitorCheckIn(code)))
            }.status)
            assertEquals(HttpStatusCode.OK, client.post(f.path("visitors/$id/check-out")) { bearerAuth(f.manager.token) }.status)
            assertEquals(HttpStatusCode.Conflict, client.post(f.path("visitors/$id/check-in")) {
                bearerAuth(f.manager.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(VisitorCheckIn(code)))
            }.status)
        }
    }

    @Test
    fun `concurrent attendance cannot exceed capacity`() = testApplication {
        CommunityFixture().use { f ->
            f.install(this)
            val now = Instant.now()
            val response = client.post(f.path("events")) {
                bearerAuth(f.manager.token); contentType(ContentType.Application.Json)
