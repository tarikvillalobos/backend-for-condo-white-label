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
        val base = System.getenv("CAMERA_PROVIDER_BASE_URL")?.takeIf { it.isNotBlank() }?.trimEnd('/')
            ?: c.fail(501, "CAMERA_PROVIDER_UNAVAILABLE", "Configure um provedor de câmeras")
        val uri = runCatching { URI(base + path) }.getOrElse { c.fail(503, "CAMERA_PROVIDER_CONFIGURATION", "URL do provedor inválida") }
        if (uri.scheme != "https" && !(System.getenv("APP_ENV") != "production" && uri.scheme == "http"))
            c.fail(503, "CAMERA_PROVIDER_CONFIGURATION", "O provedor exige HTTPS")
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
        System.getenv("CAMERA_PROVIDER_TOKEN")?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
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
    if (!timestamp(expires).isAfter(now) || timestamp(expires).isAfter(now.plusSeconds(600)))
        fail(503, "CAMERA_PROVIDER_INVALID_RESPONSE", "Validade do stream inválida")
    val protocol = response.text("protocol") ?: input.text("protocol") ?: "hls"
    if (protocol !in setOf("hls", "webrtc")) fail(503, "CAMERA_PROVIDER_INVALID_RESPONSE", "Protocolo de stream inválido")
    val providerSessionId = response.text("id") ?: fail(503, "CAMERA_PROVIDER_INVALID_RESPONSE", "Provedor não retornou identificador")
    val row = save("camera_view", obj("cameraId" to camera.id, "providerSessionId" to providerSessionId, "kind" to if (playback) "playback" else "live",
        "viewerName" to personName(), "viewerKind" to if (membership == null) "staff" else "resident", "node" to node(unitId),
        "protocol" to protocol, "startedAt" to now.toString(), "closedAt" to null, "expiresAt" to expires))
    return V1Response(obj("id" to row.id, "cameraId" to camera.id, "protocol" to protocol, "url" to url,
        "iceServers" to response["iceServers"], "expiresAt" to expires, "maxViewers" to response.number("maxViewers", 1)), 201)
}
internal fun V1Context.closeCameraStream(): V1Response {
    val row = record("camera_view", "sessionId")
    if (row.data.text("cameraId") != id("cameraId")) fail(404, "NOT_FOUND", "Sessão não encontrada")
    if (row.data.text("closedAt") == null) {
        CameraProvider.request(this, "DELETE", "/sessions", obj("id" to row.data["providerSessionId"]))
        change(row, obj("closedAt" to now.toString()), "camera.session_closed")
    }
    return V1Response(status = 204)
}
internal fun V1Context.recordings(): V1Response {
    val camera = requireCamera(true)
    val response = CameraProvider.request(this, "POST", "/recordings/search", obj("cameraRef" to camera.data["providerRef"],
        "since" to query["since"], "until" to query["until"], "cursor" to query["cursor"], "limit" to (query["limit"]?.toIntOrNull() ?: 50)))
    audit("camera.recordings_listed", camera)
    return V1Response(project("RecordingPage", response))
}
