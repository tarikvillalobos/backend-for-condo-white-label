package com.community.api.community

import com.community.api.core.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.*

class ConciergeEscalationTest {
    @Test
    fun `escalation reason is staff only while priority and generic notice reach the owner`() = testApplication {
        CommunityFixture().use { f ->
            f.install(this)
            val created = client.post(f.path("requests")) {
                bearerAuth(f.resident.token); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RequestInput("Water leak", "Water in hallway")))
            }
            val id = json.decodeFromString<Record>(created.bodyAsText()).id
