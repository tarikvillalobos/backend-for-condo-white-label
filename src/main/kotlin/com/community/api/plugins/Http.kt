package com.community.api.plugins

import com.community.api.core.ApiException

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.util.UUID
import io.ktor.util.AttributeKey
import io.ktor.server.response.respondText
import io.ktor.http.ContentType

@Serializable
data class ApiError(val code: String, val message: String, val requestId: String?)

private val responseHeaders = createApplicationPlugin("PrivateApiHeaders") {
    onCall { call ->
        call.response.headers.append("Cache-Control", "no-store")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
    }
}

fun Application.configureHttp() {
    install(responseHeaders)
    install(RequestBodyLimit) { bodyLimit { call -> if (call.request.uri.startsWith("/v1/files/")) 10L * 1024 * 1024 else 3L * 1024 * 1024 } }
    install(ContentNegotiation) { json(com.community.api.core.json) }
    install(CallId) {
        generate { UUID.randomUUID().toString() }
        replyToHeader("X-Request-ID")
    }
    install(CallLogging) {
        // Never include request bodies, query parameters, credentials, or personal data.
        format { call -> "${call.request.httpMethod.value} ${call.response.status()?.value} requestId=${call.callId}" }
    }
    install(StatusPages) {
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(HttpStatusCode.PayloadTooLarge, ApiError("payload_too_large", "Request exceeds 3 MiB", call.callId))
        }
        exception<ApiException> { call, cause ->
            call.respond(HttpStatusCode.fromValue(cause.status), ApiError(cause.code, cause.message, call.callId))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Invalid request", call.callId))
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respond(HttpStatusCode.UnsupportedMediaType, ApiError("unsupported_media_type", "Unsupported content type", call.callId))
        }
        exception<CannotTransformContentToTypeException> { call, _ ->
            call.respond(HttpStatusCode.UnsupportedMediaType, ApiError("unsupported_media_type", "Unsupported content type", call.callId))
        }
        exception<Exception> { call, cause ->
            if (cause is CancellationException) throw cause
            // Exception messages may contain secrets; correlate by request ID instead.
            this@configureHttp.log.error("Unhandled {} requestId={}", cause.javaClass.simpleName, call.callId)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "An unexpected error occurred", call.callId))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ApiError("not_found", "Resource not found", call.callId))
        }
    }
}
