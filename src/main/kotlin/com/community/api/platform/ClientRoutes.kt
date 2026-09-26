package com.community.api.platform

import com.community.api.core.*
import com.community.api.identity.requireRecentAuthentication
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.platformRoutes(db: Database) {
    clientRoutes(db)
    locationRoutes(db)
    membershipRoutes(db)
    reportRoutes(db)
    attachmentRoutes(db)
}

private fun Route.clientRoutes(db: Database) {
    route("/api/v1") {
        get("/configuration") {
