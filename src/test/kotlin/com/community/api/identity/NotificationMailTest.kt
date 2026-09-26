package com.community.api.identity

import com.community.api.community.*
import com.community.api.core.*
import com.community.api.platform.ClientSettings
import com.community.api.platform.Location
import kotlinx.coroutines.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class NotificationMailTest {
    private val config = MailConfig("localhost", 1025, "community@example.com", startTls = false)

    @Test
    fun `notification email is generic and delivered once without changing read state`() = runBlocking {
        Database.memory().use { db ->
            val notification = db.notificationFixture()
            val messages = mutableListOf<MailMessage>()
            val sender = MailSender { _, message -> messages += message }
            assertEquals(MailBatchResult(1, 0), deliverNotificationMailBatch(db, config, sender))
            assertEquals(MailBatchResult(0, 0), deliverNotificationMailBatch(db, config, sender))
            assertEquals(testEmail, messages.single().to)
            assertFalse(messages.single().text.contains("private", ignoreCase = true))
            assertFalse(messages.single().subject.contains("private", ignoreCase = true))
            assertEquals("You have a new notification. Open the app to view it.", messages.single().text)
            val unchanged = db.tx { it.get("notification", notification.id, tenantA)!! }
            assertEquals(notification, unchanged)
            val delivery = db.tx { it.list("notification_delivery", tenantA).single() }
            assertFalse(delivery.data.toString().contains(testEmail))
            assertFalse(delivery.data.toString().contains("private"))
            assertEquals("accepted", db.tx { it.notificationMailStatus(notification)!!.status })
        }
    }

    @Test
    fun `email opt out suppresses pending mail and does not replay when enabled again`() = runBlocking {
        Database.memory().use { db ->
            val notification = db.notificationFixture()
            db.tx { it.setEmailPreference(notification.ownerId!!, false) }
            val sender = MailSender { _, _ -> fail("Opted-out notification sent") }
            assertEquals(MailBatchResult(0, 0), deliverNotificationMailBatch(db, config, sender))
            assertEquals("suppressed", db.tx { it.notificationMailStatus(notification)!!.status })
            db.tx { it.setEmailPreference(notification.ownerId!!, true) }
            assertEquals(MailBatchResult(0, 0), deliverNotificationMailBatch(db, config, sender))
        }
    }

    @Test
    fun `inactive account membership location or module prevents notification delivery`() = runBlocking {
        for (disabled in listOf("account", "membership", "location", "client-module")) {
            Database.memory().use { db ->
                val notification = db.notificationFixture()
                db.tx { tx ->
                    when (disabled) {
                        "account" -> tx.get("account", notification.ownerId!!, tenantA)!!.let { tx.update(it, body(it.decode<Account>().copy(active = false))) }
                        "membership" -> tx.list("membership", tenantA).single().let { tx.update(it, body(it.decode<Membership>().copy(active = false))) }
                        "location" -> tx.get("location", notification.locationId!!, tenantA)!!.let { tx.update(it, body(it.decode<Location>().copy(active = false))) }
                        else -> tx.get("client", tenantA, tenantA)!!.let { tx.update(it, body(it.decode<ClientSettings>().copy(features = emptySet()))) }
                    }
                }
                val result = deliverNotificationMailBatch(db, config, MailSender { _, _ -> fail("Sent despite disabled $disabled") })
                assertEquals(MailBatchResult(0, 0), result)
                assertEquals("suppressed", db.tx { it.notificationMailStatus(notification)!!.status })
            }
        }
    }

    @Test
    fun `preferences are rechecked after claim before sending`() = Database.memory().use { db ->
        val notification = db.notificationFixture()
        val claim = db.tx { it.claimNotificationDelivery() }!!
        db.tx { it.setEmailPreference(notification.ownerId!!, false) }
        assertNull(db.tx { it.recheckNotificationDelivery(claim) })
        assertEquals("suppressed", db.tx { it.notificationMailStatus(notification)!!.status })
    }

    @Test
    fun `failed notification delivery backs off and obeys opt out before retry`() = runBlocking {
        Database.memory().use { db ->
            val notification = db.notificationFixture()
            val failure = MailSender { _, _ -> error("secret provider credential") }
            assertEquals(MailBatchResult(0, 1), deliverNotificationMailBatch(db, config, failure))
            assertEquals(MailBatchResult(0, 0), deliverNotificationMailBatch(db, config, failure))
            assertEquals("smtp_delivery_failed", db.tx { it.notificationMailStatus(notification)!!.failure })
            db.tx { tx ->
                val row = tx.list("notification_delivery", tenantA).single()
                tx.update(row, body(row.decode<NotificationEmailDelivery>().copy(nextAttemptAt = Instant.now().minusSeconds(1).toString())))
                tx.setEmailPreference(notification.ownerId!!, false)
            }
            assertEquals(MailBatchResult(0, 0), deliverNotificationMailBatch(db, config, MailSender { _, _ -> fail("Opt-out ignored on retry") }))
        }
    }

    @Test
    fun `concurrent notification workers share durable claims`() = runBlocking {
        Database.memory().use { db ->
            db.notificationFixture()
            val calls = AtomicInteger()
            val sender = MailSender { _, _ -> delay(30); calls.incrementAndGet() }
            coroutineScope {
                listOf(async { deliverNotificationMailBatch(db, config, sender) },
                    async { deliverNotificationMailBatch(db, config, sender) }).awaitAll()
            }
            assertEquals(1, calls.get())
        }
    }

    private fun Database.notificationFixture(): Record {
        val account = seedIdentity()
        return tx { tx ->
            tx.update(tx.get("client", tenantA, tenantA)!!, body(ClientSettings("Test client")))
            val location = tx.create("location", tenantA, data = body(Location("Test location")))
            tx.create("membership", tenantA, location.id, account.id, body(Membership(account.id, location.id)))
            tx.create("notification", tenantA, location.id, account.id, body(InboxNotification("Private package", "Private pickup credential", "2026-09-26T00:00:00Z")))
        }
    }

    private fun Tx.setEmailPreference(userId: String, enabled: Boolean) {
        val existing = list("notification_preferences", tenantA, ownerId = userId).firstOrNull()
        val data = body(NotificationPreferences(email = enabled))
        if (existing == null) create("notification_preferences", tenantA, ownerId = userId, data = data) else update(existing, data)
    }
}
