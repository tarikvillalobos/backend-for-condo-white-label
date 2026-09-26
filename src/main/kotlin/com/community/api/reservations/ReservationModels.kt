package com.community.api.reservations

import kotlinx.serialization.Serializable

@Serializable
data class FacilityData(
    val name: String,
    val timeZone: String = "America/Sao_Paulo",
    val capacity: Int = 1,
    val opensAt: String = "08:00",
    val closesAt: String = "22:00",
    val weekdays: Set<Int> = (1..7).toSet(),
    val maxDurationMinutes: Int = 240,
    val minNoticeMinutes: Int = 0,
    val maxDaysAhead: Int = 90,
    val maxActivePerMember: Int = 5,
    val requiresApproval: Boolean = false,
    val maintenance: Boolean = false,
)

@Serializable
data class FacilityView(val id: String, val rules: FacilityData)

@Serializable
data class CreateReservation(
    val facilityId: String,
    val startsAt: String,
    val endsAt: String,
    val attendees: Int = 1,
    val note: String = "",
)

@Serializable
data class ReservationEvent(val action: String, val actorId: String, val at: String)

@Serializable
data class ReservationData(
    val request: CreateReservation,
    val status: String,
    val history: List<ReservationEvent>,
