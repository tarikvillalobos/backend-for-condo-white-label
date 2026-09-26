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
