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
