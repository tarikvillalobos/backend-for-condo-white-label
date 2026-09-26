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
