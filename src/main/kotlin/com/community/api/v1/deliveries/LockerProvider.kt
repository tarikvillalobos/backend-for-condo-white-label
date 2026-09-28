package com.community.api.v1.deliveries

import com.community.api.v1.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.*

internal object LockerProvider {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    fun open(c: V1Context, lockerId: String, deviceId: String, code: String, commandId: String, reason: String,
        baseUrl: String? = System.getenv("LOCKER_PROVIDER_BASE_URL"), credential: String? = System.getenv("LOCKER_PROVIDER_TOKEN"),
        allowHttp: Boolean = System.getenv("APP_ENV") != "production") {
        val base = baseUrl?.trimEnd('/')
            ?: c.fail(501, "PROVIDER_NOT_CONFIGURED", "Configure um provedor de lockers")
        val token = credential?.takeIf { it.isNotBlank() }
            ?: c.fail(503, "PROVIDER_NOT_CONFIGURED", "Configure a credencial do provedor de lockers")
        val uri = runCatching { URI(base) }.getOrElse { c.fail(503, "PROVIDER_NOT_CONFIGURED", "URL inválida do provedor") }
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null ||
            (uri.scheme != "https" && !(allowHttp && uri.scheme == "http")))
            c.fail(503, "PROVIDER_NOT_CONFIGURED", "O provedor de lockers exige URL HTTPS válida")
        val path = "/lockers/$lockerId/compartments/${URLEncoder.encode(code, Charsets.UTF_8).replace("+", "%20")}/open"
        val request = HttpRequest.newBuilder(URI(base + path)).timeout(Duration.ofSeconds(8))
            .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
            .header("Idempotency-Key", commandId)
            .POST(HttpRequest.BodyPublishers.ofString(obj("commandId" to commandId, "deviceId" to deviceId,
                "reason" to reason).toString())).build()
        val response = runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { c.fail(503, "SERVICE_UNAVAILABLE", "Provedor de lockers indisponível") }
        if (response.statusCode() != 202) c.fail(503, "SERVICE_UNAVAILABLE", "Provedor não aceitou o comando")
        if (response.body().isNotBlank()) {
            val accepted = runCatching { Json.parseToJsonElement(response.body()).jsonObject }
                .getOrElse { c.fail(503, "SERVICE_UNAVAILABLE", "Resposta inválida do provedor") }
            if (accepted.string("commandId") != commandId) c.fail(503, "SERVICE_UNAVAILABLE", "Identificador do comando divergente")
        }
    }
}
