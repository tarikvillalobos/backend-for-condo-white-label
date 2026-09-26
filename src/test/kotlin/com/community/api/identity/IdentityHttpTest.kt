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
                setBody("""{"tenantId":"$tenantA","email":"$testEmail","password":"$testPassword"}""")
            }
            assertEquals(HttpStatusCode.OK, login.status)
            val token = Json.parseToJsonElement(login.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
            for (path in listOf("/api/v1/me", "/api/v1/me/sessions")) {
                val response = client.get(path) { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                assertFalse(response.bodyAsText().contains("Hash"))
                assertFalse(response.bodyAsText().contains("pbkdf2"))
                assertFalse(response.bodyAsText().contains(token))
            }
            assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/logout") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(token) }.status)
        }
    }

    @Test
    fun `HTTP failed recovery response matches unknown account without returning credential`() = Database.memory().use { db ->
        db.seedIdentity()
        testApplication {
