package com.community.api.v1

import com.community.api.core.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

private val fileDirectory: Path get() = Path.of(System.getenv("UPLOAD_DIRECTORY") ?: "data/uploads").toAbsolutePath()
private val publicBase: String get() = (System.getenv("PUBLIC_BASE_URL") ?: "http://localhost:8080").trimEnd('/')

private fun V1Context.fileTicket(id: String, action: String, seconds: Long = 900): String = seal(obj(
    "tenant" to tenantId,"brand" to brandId,"id" to id,"action" to action,"owner" to principal?.userId,
    "expires" to now.plusSeconds(seconds)).toString())
