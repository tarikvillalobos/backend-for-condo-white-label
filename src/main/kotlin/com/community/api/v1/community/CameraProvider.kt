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
