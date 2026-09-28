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
