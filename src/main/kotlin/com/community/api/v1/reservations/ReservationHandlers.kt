package com.community.api.v1.reservations

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.*

fun reservationHandlers(): Map<String, V1Handler> = mapOf(
    "listSpaces" to V1Handler(::spaces), "adminListSpaces" to V1Handler(::spaces),
    "adminCreateSpace" to V1Handler(::saveSpace), "adminUpdateSpace" to V1Handler(::saveSpace),
    "getAvailability" to V1Handler(::availability), "createReservation" to V1Handler(::createReservation),
    "listReservations" to V1Handler(::reservations), "adminListReservations" to V1Handler(::reservations),
    "getReservation" to V1Handler { c -> V1Response(reservationView(c, reservation(c))) },
    "cancelReservation" to V1Handler(::transition), "adminCancelReservation" to V1Handler(::transition),
    "adminApproveReservation" to V1Handler(::transition), "adminRejectReservation" to V1Handler(::transition),
    "adminBlockSpace" to V1Handler(::blockSpace), "adminUnblockSpace" to V1Handler(::unblockSpace),
)

internal fun condominium(c: V1Context): String = c.locationId ?: c.fail(404, "NOT_FOUND", "Condominium not found")
internal fun space(c: V1Context, id: String = c.path.getValue("spaceId")): Record =
