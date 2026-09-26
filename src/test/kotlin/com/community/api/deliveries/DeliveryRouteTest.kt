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
