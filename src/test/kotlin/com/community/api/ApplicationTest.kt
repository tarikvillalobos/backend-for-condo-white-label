package com.community.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class ApplicationTest {
    @Test
    fun `health endpoints return JSON and a distinct generated request ID`() = testApplication {
        application { module() }

        val responses = listOf("/health/live", "/health/ready").map { path ->
            client.get(path) { header("X-Request-ID", "caller-provided-id") }
        }

        responses.forEach { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertEquals("UP", response.jsonBody()["status"]?.jsonPrimitive?.content)
            assertNotEquals("caller-provided-id", response.requestId())
        }
        assertNotEquals(responses[0].requestId(), responses[1].requestId())
    }

    @Test
    fun `missing routes return a correlated JSON error`() = testApplication {
        application { module() }

        client.get("/resource-that-does-not-exist").assertError(
            HttpStatusCode.NotFound,
            "not_found",
            "Resource not found",
        )
    }

    @Test
    fun `unexpected failures do not disclose exception details`() = testApplication {
        application {
            module()
            routing {
                get("/test/failure") { error("database-password=do-not-disclose") }
            }
        }

        val response = client.get("/test/failure")

        response.assertError(
            HttpStatusCode.InternalServerError,
            "internal_error",
            "An unexpected error occurred",
        )
        val body = response.bodyAsText()
        assertFalse(body.contains("database-password"))
        assertFalse(body.contains("do-not-disclose"))
        assertFalse(body.contains("IllegalStateException"))
    }

    @Test
    fun `malformed JSON returns a safe bad request error`() = testApplication {
        application {
            module()
            routing {
                post("/test/json") { call.respond(call.receive<JsonObject>()) }
            }
        }

        client.post("/test/json") {
            contentType(ContentType.Application.Json)
            setBody("{\"secret\": \"private-value\"")
        }.assertError(HttpStatusCode.BadRequest, "bad_request", "Invalid request")
    }

    @Test
    fun `unsupported request content returns a JSON media type error`() = testApplication {
        application {
            module()
            routing {
                post("/test/json") { call.respond(call.receive<JsonObject>()) }
            }
        }

        client.post("/test/json") {
            contentType(ContentType.Text.Plain)
            setBody("private-value")
        }.assertError(
            HttpStatusCode.UnsupportedMediaType,
            "unsupported_media_type",
            "Unsupported content type",
        )
    }

    private fun HttpResponse.requestId(): String {
        val id = assertNotNull(headers["X-Request-ID"])
        assertEquals(id, UUID.fromString(id).toString())
        return id
    }

    private suspend fun HttpResponse.jsonBody(): JsonObject =
