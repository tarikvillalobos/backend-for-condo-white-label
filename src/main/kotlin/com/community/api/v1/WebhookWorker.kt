package com.community.api.v1

import com.community.api.core.Database
import com.community.api.core.json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*

private data class WebhookJob(val tenant:String,val brand:String,val id:String,val url:String,val secret:String,
    val lease:String,val sequence:Long,val eventId:String,val action:String,val occurredAt:String,val target:JsonElement?)
private val webhookClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
    .followRedirects(HttpClient.Redirect.NEVER).build()

