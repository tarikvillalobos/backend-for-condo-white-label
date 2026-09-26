package com.community.api.identity

import com.community.api.core.*
import kotlinx.coroutines.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class MailDeliveryTest {
    private val config = MailConfig("localhost", 2525, "community@example.com", startTls = false)

    @Test
    fun `successful SMTP dispatch removes secret delivery but keeps consumable challenge`() = runBlocking {
        Database.memory().use { db ->
            db.seedIdentity()
            db.tx { it.requestRecovery(EmailRequest(tenantA, testEmail), "test-host", false) }.unwrap()
            val before = db.tx { it.list("auth_delivery", tenantA).single().decode<AuthDelivery>() }
            val messages = mutableListOf<MailMessage>()
            val result = deliverAuthMailBatch(db, config, MailSender { _, message -> messages += message })
            assertEquals(MailBatchResult(1, 0), result)
            assertEquals(testEmail, messages.single().to)
            assertTrue(messages.single().text.contains(before.credential))
            assertTrue(db.tx { it.list("auth_delivery", tenantA).isEmpty() })
            db.tx { it.activate(ActivationRequest(before.credential, testPassword), "test-host", "recovery") }.unwrap()
        }
    }

    @Test
    fun `failures persist sanitized status and backoff with at most five attempts`() = runBlocking {
        Database.memory().use { db ->
            db.seedIdentity()
            db.tx { it.requestRecovery(EmailRequest(tenantA, testEmail), "test-host", false) }.unwrap()
            val calls = AtomicInteger()
            val sender = MailSender { _, _ -> calls.incrementAndGet(); error("secret-provider-password") }
            repeat(5) { attempt ->
                val result = deliverAuthMailBatch(db, config, sender)
                assertEquals(MailBatchResult(0, 1), result)
                val data = db.tx { it.list("auth_delivery", tenantA).single().decode<AuthDelivery>() }
                assertEquals(attempt + 1, data.attempts)
                assertEquals("smtp_delivery_failed", data.lastFailure)
                assertEquals(MailBatchResult(0, 0), deliverAuthMailBatch(db, config, sender))
                db.tx { tx ->
                    val record = tx.list("auth_delivery", tenantA).single()
                    tx.update(record, body(record.decode<AuthDelivery>().copy(nextAttemptAt = Instant.now().minusSeconds(1).toString())))
                }
            }
            assertEquals("failed", db.tx { it.list("auth_delivery", tenantA).single().decode<AuthDelivery>().status })
            assertEquals(MailBatchResult(0, 0), deliverAuthMailBatch(db, config, sender))
            assertEquals(5, calls.get())
        }
    }

    @Test
    fun `concurrent workers cannot deliver the same active lease`() = runBlocking {
        Database.memory().use { db ->
            db.seedIdentity()
            db.tx { it.requestRecovery(EmailRequest(tenantA, testEmail), "test-host", false) }.unwrap()
            val calls = AtomicInteger()
            val sender = MailSender { _, _ -> delay(50); calls.incrementAndGet() }
            val results = coroutineScope {
