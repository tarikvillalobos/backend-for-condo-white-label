package com.community.api.community

import com.community.api.core.Database
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

fun Route.communityRoutes(db: Database) {
    route("/api/v1/locations/{locationId}") {
        announcementRoutes(db)
        eventRoutes(db)
        petRoutes(db)
        requestRoutes(db)
        visitorRoutes(db)
        vehicleRoutes(db)
        maintenanceRoutes(db)
        documentRoutes(db)
        contactRoutes(db)
        cameraRoutes(db)
    }
    notificationRoutes(db)
}
