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
