package com.community.api.platform

import com.community.api.core.*
import com.community.api.identity.Account
import com.community.api.identity.SessionData
import com.community.api.identity.issueSession
import com.community.api.module
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.*

class PlatformTest {
    @Test
    fun `tenant access cannot be selected by knowing an identifier`() {
        val f = PlatformFixture()
        f.db.use { db ->
            db.tx { tx ->
                val other = tx.create("location", "other", data = body(Location("Other")))
                assertEquals(404, assertFailsWith<ApiException> { tx.authorize(f.actor, other.id, "locations.read") }.status)
                val member = tx.create("membership", f.tenant, f.location, f.resident, body(Membership(f.resident, f.location)))
                val resident = Actor(f.resident, f.tenant, "test")
                assertFalse(tx.authorize(resident, f.location, "packages.read.own", "packages").can("packages.read.all"))
                tx.update(member, body(member.decode<Membership>().copy(active = false)))
                assertEquals(403, assertFailsWith<ApiException> { tx.authorize(resident, f.location, "packages.read.own") }.status)
            }
        }
    }

    @Test
    fun `delegation cannot exceed grants or broaden location scope`() {
        val f = PlatformFixture()
        f.db.use { db -> db.tx { tx ->
            val manager = Context(Actor(f.admin, f.tenant, "test"), f.location, setOf("memberships.manage", "locations.read"))
            assertFailsWith<ApiException> { tx.saveMembership(manager, Membership(f.resident, f.location, role = "property_manager")) }
            assertFailsWith<ApiException> { tx.saveMembership(manager, Membership(f.resident, role = "client_admin")) }
        } }
    }

    @Test
    fun `inactive alternate administrator cannot allow final administrator removal`() {
        val f = PlatformFixture()
        f.db.use { db -> db.tx { tx ->
            val second = tx.create("account", f.tenant, data = body(Account("inactive@example.com", "Inactive", "unused", false)))
            tx.create("membership", f.tenant, ownerId = second.id, data = body(Membership(second.id, role = "client_admin")))
            val current = tx.list("membership", f.tenant, ownerId = f.admin).single()
            assertEquals(409, assertFailsWith<ApiException> {
                tx.saveMembership(Context(f.actor, null, setOf("*")), current.decode<Membership>().copy(active = false), current)
            }.status)
        } }
    }

    @Test
    fun `location may be suspended and restored by client administrator`() = testApplication {
        val f = PlatformFixture()
        application { module(f.db) }
        for (active in listOf(false, true)) {
            val response = client.put("/api/v1/locations/${f.location}") {
                bearerAuth(f.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(Location("Location", active = active)))
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        }
        val response = client.put("/api/v1/client") {
            bearerAuth(f.token); contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ClientSettings("Client", active = false)))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `disabled features are absent from reports and blocked in exports`() = testApplication {
        val f = PlatformFixture()
        f.db.tx { tx ->
            val location = tx.requireRecord("location", f.location, f.tenant)
            tx.update(location, body(location.decode<Location>().copy(features = allFeatures - "packages")))
        }
        application { module(f.db) }
        val report = client.get("/api/v1/locations/${f.location}/reports") { bearerAuth(f.token) }
        assertEquals(HttpStatusCode.OK, report.status)
        assertFalse("package" in json.parseToJsonElement(report.bodyAsText()).jsonObject.getValue("counts").jsonObject)
        val export = client.get("/api/v1/locations/${f.location}/reports/package/export") { bearerAuth(f.token) }
        assertEquals(HttpStatusCode.Forbidden, export.status)
    }

    @Test
    fun `role grants require recent verification`() = testApplication {
        val f = PlatformFixture()
        f.db.tx { tx ->
            val session = tx.requireRecord("session", f.actor.sessionId, f.tenant)
            tx.update(session, body(session.decode<SessionData>().copy(verifiedAt = Instant.now().minusSeconds(601).toString())))
        }
        application { module(f.db) }
        val response = client.post("/api/v1/roles") {
            bearerAuth(f.token); contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RoleDefinition("custom", setOf("packages.read.own"))))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("verification_required", json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `retries do not issue another invitation or expose credentials`() = testApplication {
        val f = PlatformFixture()
        application { module(f.db) }
        val request = InvitationRequest("new@example.com", "New resident", f.location)
        suspend fun issue() = client.post("/api/v1/locations/${f.location}/invitations") {
            bearerAuth(f.token); contentType(ContentType.Application.Json)
            header("Idempotency-Key", "single-invitation")
            setBody(json.encodeToString(request))
        }
        val first = issue()
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val second = issue()
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertFalse(second.bodyAsText().contains(json.parseToJsonElement(first.bodyAsText()).jsonObject.getValue("token").jsonPrimitive.content))
        assertEquals(1, f.db.tx { it.list("auth_challenge", f.tenant).size })
    }
}

internal class PlatformFixture {
    val db = Database.memory()
    val tenant = "tenant-platform"
    val admin: String
    val resident: String
    val location: String
    val token: String
    val actor: Actor

    init {
        val setup = db.tx { tx ->
            tx.create("client", tenant, data = body(ClientSettings("Client")), id = tenant)
            val admin = tx.create("account", tenant, data = body(Account("admin@example.com", "Admin", "unused")))
            val resident = tx.create("account", tenant, data = body(Account("resident@example.com", "Resident", "unused")))
            tx.create("membership", tenant, ownerId = admin.id, data = body(Membership(admin.id, role = "client_admin")))
            val location = tx.create("location", tenant, data = body(Location("Location")))
            listOf(admin.id, resident.id, location.id, tx.issueSession(admin, "Test").accessToken)
        }
        admin = setup[0]; resident = setup[1]; location = setup[2]; token = setup[3]
        actor = Actor(admin, tenant, token.split('.')[1])
    }
}
