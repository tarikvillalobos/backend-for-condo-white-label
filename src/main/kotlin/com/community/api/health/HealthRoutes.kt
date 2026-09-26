package com.community.api.health

import com.community.api.core.Database
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String)

fun Route.healthRoutes() {
    route("/health") {
        get("/live") { call.respond(HealthResponse("UP")) }
        // No external dependencies exist yet. Add their checks before introducing them.
        get("/ready") { call.respond(HealthResponse("UP")) }
    }
}
