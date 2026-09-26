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
                setBody(json.encodeToString(EventInput("Yoga", "Morning class", now.plusSeconds(3600).toString(), now.plusSeconds(7200).toString(), 1)))
            }
            val id = json.decodeFromString<Record>(response.bodyAsText()).id
            val responses = coroutineScope {
                listOf(f.resident, f.other).map { user -> async {
                    client.post(f.path("events/$id/attendance")) { bearerAuth(user.token) }
                } }.awaitAll()
            }
            assertEquals(listOf(200, 409), responses.map { it.status.value }.sorted())
            assertEquals(1, f.db.tx { it.list("attendance", f.tenant, f.location).size })
        }
    }

    @Test
    fun `publication scheduling and visitor expiry boundaries are precise`() {
        val now = Instant.parse("2030-01-01T12:00:00Z")
        val notice = Announcement(AnnouncementInput("Notice", "Message", publishAt = now.toString(), expiresAt = now.plusSeconds(60).toString()))
        assertFalse(notice.visibleAt(now.minusNanos(1)))
        assertTrue(notice.visibleAt(now))
        assertFalse(notice.visibleAt(now.plusSeconds(60)))
        val invite = VisitorInvite(VisitorInput("Guest", "Visit", now.toString(), now.plusSeconds(60).toString()), credentialHash("test-code"))
        assertEquals(409, assertFailsWith<ApiException> { invite.checkIn("test-code", now.minusNanos(1)) }.status)
        assertEquals(1, invite.checkIn("test-code", now).visits)
        assertEquals(409, assertFailsWith<ApiException> { invite.checkIn("test-code", now.plusSeconds(60)) }.status)
        assertEquals(403, assertFailsWith<ApiException> { invite.checkIn("wrong-code", now) }.status)
    }

    @Test
    fun `privileged request and maintenance transitions reject escalation`() {
        assertEquals(403, assertFailsWith<ApiException> { validateRequestTransition("in_progress", "resolved", false) }.status)
        validateRequestTransition("resolved", "open", false)
        assertEquals(409, assertFailsWith<ApiException> { validateRequestTransition("cancelled", "open", true) }.status)
        validateWorkOrderTransition("scheduled", "in_progress")
        assertEquals(409, assertFailsWith<ApiException> { validateWorkOrderTransition("scheduled", "completed") }.status)
    }
}
