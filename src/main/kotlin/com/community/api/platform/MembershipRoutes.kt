package com.community.api.platform

import com.community.api.core.*
import com.community.api.identity.Account
import com.community.api.identity.requireRecentAuthentication
import com.community.api.identity.revokeAccountCredentials
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

fun Route.membershipRoutes(db: Database) {
    membershipScope(db, "/api/v1")
    membershipScope(db, "/api/v1/locations/{locationId}")
    route("/api/v1/accounts") {
        get {
            call.respondPage(db.query { tx ->
