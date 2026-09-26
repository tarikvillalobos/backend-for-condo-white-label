package com.community.api.community

import com.community.api.core.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.*

class ConciergeEscalationTest {
    @Test
    fun `escalation reason is staff only while priority and generic notice reach the owner`() = testApplication {
        CommunityFixture().use { f ->
            f.install(this)
            val created = client.post(f.path("requests")) {
                bearerAuth(f.resident.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RequestInput("Water leak", "Water in hallway")))
            }
            val id = json.decodeFromString<Record>(created.bodyAsText()).id
            val input = RequestEscalationInput("Staff investigation details", "urgent", f.manager.id, Instant.now().plusSeconds(3600).toString())
            assertEquals(HttpStatusCode.Forbidden, client.post(f.path("requests/$id/escalate")) {
                bearerAuth(f.resident.token); contentType(ContentType.Application.Json); setBody(json.encodeToString(input))
            }.status)
            val result = client.post(f.path("requests/$id/escalate")) {
                bearerAuth(f.manager.token); contentType(ContentType.Application.Json); setBody(json.encodeToString(input))
            }
            assertEquals(HttpStatusCode.OK, result.status)
            val state = json.decodeFromString<Record>(result.bodyAsText()).decode<ResidentRequest>()
            assertEquals("urgent", state.content.priority)
            assertEquals("open", state.status)
            assertEquals(f.manager.id, state.assignedTo)
            assertEquals(input.dueAt, state.dueAt)
            assertEquals(HttpStatusCode.Forbidden, client.get(f.path("requests/$id/escalations")) { bearerAuth(f.resident.token) }.status)
            val history = client.get(f.path("requests/$id/escalations")) { bearerAuth(f.manager.token) }
            assertTrue(history.bodyAsText().contains(input.reason))
            for (path in listOf(f.path("requests/$id"), f.path("requests/$id/comments"), "/api/v1/notifications")) {
                assertFalse(client.get(path) { bearerAuth(f.resident.token) }.bodyAsText().contains(input.reason))
            }
            assertTrue(client.get("/api/v1/notifications") { bearerAuth(f.resident.token) }.bodyAsText().contains("Request escalated"))
            assertEquals(HttpStatusCode.Conflict, client.post(f.path("requests/$id/escalate")) {
                bearerAuth(f.manager.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(input.copy(priority = "normal")))
            }.status)
            assertEquals(1, f.db.tx { it.list("request_escalation", f.tenant, f.location).size })
        }
    }

    @Test
    fun `escalation rejects a resident assignee without leaving partial history`() = testApplication {
        CommunityFixture().use { f ->
            f.install(this)
            val row = f.db.tx { it.create("request", f.tenant, f.location, f.resident.id, body(ResidentRequest(RequestInput("Help", "Need repair")))) }
            val response = client.post(f.path("requests/${row.id}/escalate")) {
                bearerAuth(f.manager.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RequestEscalationInput("Assigning", "high", f.other.id)))
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(0, f.db.tx { it.list("request_escalation", f.tenant, f.location).size })
            assertEquals("normal", f.db.tx { it.get("request", row.id, f.tenant)!!.decode<ResidentRequest>().content.priority })
        }
    }

    @Test
    fun `shift notes require explicit staff permission for reading and writing`() = testApplication {
        CommunityFixture().use { f ->
            f.install(this)
            val note = ShiftNoteInput("Incident handover for the evening team", incident = true)
            assertEquals(HttpStatusCode.Forbidden, client.post(f.path("shift-notes")) {
                bearerAuth(f.resident.token); contentType(ContentType.Application.Json); setBody(json.encodeToString(note))
            }.status)
            assertEquals(HttpStatusCode.Created, client.post(f.path("shift-notes")) {
                bearerAuth(f.manager.token); contentType(ContentType.Application.Json); setBody(json.encodeToString(note))
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get(f.path("shift-notes")) { bearerAuth(f.resident.token) }.status)
            assertTrue(client.get(f.path("shift-notes")) { bearerAuth(f.manager.token) }.bodyAsText().contains(note.message))
            f.db.tx { tx ->
                val membership = tx.list("membership", f.tenant, f.location, f.other.id).single()
                tx.update(membership, body(membership.decode<Membership>().copy(role = "concierge")))
            }
