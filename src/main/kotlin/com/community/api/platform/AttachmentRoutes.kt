package com.community.api.platform

import com.community.api.core.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Base64

@Serializable
data class AttachmentInput(val filename: String, val contentType: String, val contentBase64: String, val visibility: String = "private")

@Serializable
data class AttachmentData(val filename: String, val contentType: String, val contentBase64: String, val visibility: String, val size: Int, val sha256: String)

