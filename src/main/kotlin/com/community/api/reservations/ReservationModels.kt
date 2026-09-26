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

