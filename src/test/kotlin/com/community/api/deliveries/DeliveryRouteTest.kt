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
            }
            assertEquals(HttpStatusCode.Forbidden, client.get(path) { bearerAuth(token) }.status)
            db.tx { tx ->
                val location = tx.requireRecord("location", "location", "tenant")
                tx.update(location, body(location.decode<Location>().copy(features = allFeatures)))
                val member = tx.list("membership", "tenant").single()
                tx.update(member, body(member.decode<Membership>().copy(active = false)))
            }
            assertEquals(HttpStatusCode.Forbidden, client.get(path) { bearerAuth(token) }.status)
        }
    }

    @Test
    fun `locker webhook requires dedicated active location-bound credentials`() = Database.memory().use { db ->
        val humanToken = seed(db)
        val integrationToken = "tenant.hardware." + "s".repeat(43)
        val hash = MessageDigest.getInstance("SHA-256").digest(integrationToken.toByteArray()).joinToString("") { "%02x".format(it) }
        val service = DeliveryService()
        val parcel = db.tx { tx ->
            tx.create("integration", "tenant", "location", data = body(IntegrationData("Test adapter", "location", hash)), id = "hardware")
            val locker = service.saveLocker(tx, context, LockerData("Locker", listOf(Compartment("A", "A")), integrationId = "hardware"))
            service.receive(tx, context, ReceivePackage("recipient", "Parcel", lockerId = locker.id, compartmentId = "A"), "receipt")
        }
        val event = body(LockerPickupEvent("event", parcel.id, "A", "recipient", Instant.now().toString())).toString()
        testApplication {
            application { configureHttp(); routing { deliveryRoutes(db) } }
            suspend fun send(location: String, token: String) = client.post("/api/v1/locations/$location/locker-events") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(event)
            }
            assertEquals(HttpStatusCode.Unauthorized, send("location", humanToken).status)
            assertEquals(HttpStatusCode.Unauthorized, send("other-location", integrationToken).status)
            val success = send("location", integrationToken)
            assertEquals(HttpStatusCode.OK, success.status)
            assertTrue(success.bodyAsText().contains("applied"))
            assertEquals(HttpStatusCode.OK, send("location", integrationToken).status)
            db.tx { tx ->
                val integration = tx.requireRecord("integration", "hardware", "tenant")
                tx.update(integration, body(integration.decode<IntegrationData>().copy(active = false)))
            }
            assertEquals(HttpStatusCode.Unauthorized, send("location", integrationToken).status)
        }
    }
}
