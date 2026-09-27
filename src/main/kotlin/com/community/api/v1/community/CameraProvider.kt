package com.community.api.v1.community

import com.community.api.core.json
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal object CameraProvider {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    fun request(c: V1Context, method: String, path: String, body: JsonObject): JsonObject {
        val base = System.getenv("CAMERA_PROVIDER_BASE_URL")?.trimEnd('/')
            ?: c.fail(501, "CAMERA_PROVIDER_UNAVAILABLE", "Configure um provedor de câmeras")
        val uri = runCatching { URI(base + path) }.getOrElse { c.fail(503, "CAMERA_PROVIDER_CONFIGURATION", "URL do provedor inválida") }
        if (uri.scheme != "https" && !(System.getenv("APP_ENV") != "production" && uri.scheme == "http"))
            c.fail(503, "CAMERA_PROVIDER_CONFIGURATION", "O provedor exige HTTPS")
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
        System.getenv("CAMERA_PROVIDER_TOKEN")?.let { builder.header("Authorization", "Bearer $it") }
        val request = builder.method(method, HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val response = runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { c.fail(503, "CAMERA_PROVIDER_UNAVAILABLE", "Provedor de câmeras indisponível") }
        if (response.statusCode() !in 200..299) c.fail(503, "CAMERA_PROVIDER_UNAVAILABLE", "Provedor recusou a operação")
        if (response.body().isBlank()) return obj()
        return runCatching { json.parseToJsonElement(response.body()).jsonObject }
            .getOrElse { c.fail(503, "CAMERA_PROVIDER_INVALID_RESPONSE", "Resposta inválida do provedor") }
    }
}
internal fun V1Context.openCameraStream(playback: Boolean): V1Response {
    val camera = requireCamera(playback)
    if (camera.data.text("status") in setOf("maintenance", "restricted")) fail(409, "CAMERA_UNAVAILABLE", "Câmera indisponível")
    val response = CameraProvider.request(this, "POST", "/sessions", input.merge(obj("cameraRef" to camera.data["providerRef"],
        "provider" to camera.data["provider"], "recordingId" to if (playback) id("recordingId") else null, "ttlSeconds" to 120)))
    val url = response.text("url") ?: fail(503, "CAMERA_PROVIDER_INVALID_RESPONSE", "Provedor não retornou URL")
    val expires = response.text("expiresAt") ?: fail(503, "CAMERA_PROVIDER_INVALID_RESPONSE", "Provedor não retornou validade")
    val uri = runCatching { URI(url) }.getOrElse { fail(503, "CAMERA_PROVIDER_INVALID_RESPONSE", "URL inválida") }
    if (uri.userInfo != null || (uri.scheme != "https" && System.getenv("APP_ENV") == "production") || uri.scheme !in setOf("https", "http"))
        fail(503, "CAMERA_PROVIDER_INVALID_RESPONSE", "URL do stream não é segura")
