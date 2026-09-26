package com.community.api.identity

import com.community.api.community.InboxNotification
import com.community.api.community.notificationRoutes
import com.community.api.core.*
import com.community.api.platform.ClientSettings
import com.community.api.platform.Location
import com.community.api.plugins.configureHttp
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import kotlin.test.*

class NotificationDeliveryHttpTest {
    @Test
    fun `delivery status requires ownership and current notification access`() = Database.memory().use { db ->
        val owner = db.seedIdentity()
        val other = db.tx { it.create("account", tenantA, data = body(Account("other@example.com", "Other", testPasswordHash))) }
        val notification = db.tx { tx ->
            tx.update(tx.get("client", tenantA, tenantA)!!, body(ClientSettings("Test client")))
            val location = tx.create("location", tenantA, data = body(Location("Test location")))
            for (account in listOf(owner, other)) tx.create("membership", tenantA, location.id, account.id,
                body(Membership(account.id, location.id)))
            val row = tx.create("notification", tenantA, location.id, owner.id, body(InboxNotification("Private", "Private message")))
            tx.create("notification_delivery", tenantA, location.id, owner.id,
                body(NotificationEmailDelivery(status = "accepted", attempts = 1, acceptedAt = "2026-09-26T00:00:00Z")), notificationDeliveryId(row.id))
            row
        }
        val ownerToken = db.tx { it.issueSession(owner, "test").accessToken }
        val otherToken = db.tx { it.issueSession(other, "test").accessToken }
        testApplication {
            application { configureHttp(); routing { notificationRoutes(db) } }
            val path = "/api/v1/notifications/${notification.id}/delivery"
            val response = client.get(path) { bearerAuth(ownerToken) }
            assertEquals(HttpStatusCode.OK, response.status)
            val status = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("accepted", status["status"]!!.jsonPrimitive.content)
            assertEquals(setOf("status", "attempts", "acceptedAt", "failure"), status.keys)
            assertEquals(HttpStatusCode.NotFound, client.get(path) { bearerAuth(otherToken) }.status)
            db.tx { tx ->
                val membership = tx.list("membership", tenantA, ownerId = owner.id).single()
                tx.update(membership, body(membership.decode<Membership>().copy(active = false)))
            }
            assertEquals(HttpStatusCode.Forbidden, client.get(path) { bearerAuth(ownerToken) }.status)
        }
    }
}
