package com.community.api.identity

import com.community.api.core.Database
import com.community.api.plugins.configureHttp
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import kotlin.test.*

class IdentityHttpTest {
    @Test
    fun `HTTP sessions and profile never disclose hashes and logout revokes bearer`() = Database.memory().use { db ->
        db.seedIdentity()
        testApplication {
            application { configureHttp(); routing { identityRoutes(db) } }
            val login = client.post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
