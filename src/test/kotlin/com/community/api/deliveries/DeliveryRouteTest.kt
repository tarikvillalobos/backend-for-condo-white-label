package com.community.api.deliveries

import com.community.api.core.*
import com.community.api.identity.Account
import com.community.api.identity.issueSession
import com.community.api.platform.ClientSettings
import com.community.api.platform.Location
import com.community.api.plugins.configureHttp
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.*

class DeliveryRouteTest {
    private val context = Context(Actor("recipient", "tenant", "session"), "location", setOf("*"))

    private fun seed(db: Database): String = db.tx { tx ->
        tx.create("client", "tenant", data = body(ClientSettings("Tenant")), id = "tenant")
        tx.create("location", "tenant", data = body(Location("Locker shop", kind = "standalone")), id = "location")
        val account = tx.create("account", "tenant", data = body(Account("resident@example.test", "Resident", "unused")), id = "recipient")
        tx.create("membership", "tenant", "location", "recipient", body(Membership("recipient", "location")))
        tx.issueSession(account, "test").accessToken
    }

    @Test
    fun `package endpoints enforce authentication feature and membership state`() = Database.memory().use { db ->
        val token = seed(db)
        testApplication {
            application { configureHttp(); routing { deliveryRoutes(db) } }
            val path = "/api/v1/locations/location/packages"
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
            assertEquals(HttpStatusCode.OK, client.get(path) { bearerAuth(token) }.status)
            db.tx { tx ->
                val location = tx.requireRecord("location", "location", "tenant")
                tx.update(location, body(location.decode<Location>().copy(features = emptySet())))
