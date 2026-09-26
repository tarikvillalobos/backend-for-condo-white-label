package com.community.api.community

import com.community.api.core.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import kotlin.test.*

class CommunitySecurityTest {
    @Test
    fun `request ownership and internal notes are enforced`() = testApplication {
        CommunityFixture().use { f ->
            f.install(this)
            val created = client.post(f.path("requests")) {
                bearerAuth(f.resident.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RequestInput("Broken light", "Hallway light failed")))
            }
            assertEquals(HttpStatusCode.Created, created.status)
