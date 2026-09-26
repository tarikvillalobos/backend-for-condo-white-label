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
