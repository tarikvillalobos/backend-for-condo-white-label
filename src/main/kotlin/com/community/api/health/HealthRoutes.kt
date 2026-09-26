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

fun Route.healthRoutes(database: Database) {
    route("/health") {
        get("/live") { call.respond(HealthResponse("UP")) }
        get("/ready") {
            val healthy = withContext(Dispatchers.IO) { database.healthy() }
            call.respond(if (healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, HealthResponse(if (healthy) "UP" else "DOWN"))
        }
    }
}
