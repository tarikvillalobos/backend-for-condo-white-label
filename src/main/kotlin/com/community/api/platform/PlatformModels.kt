package com.community.api.platform

import com.community.api.core.allFeatures
import kotlinx.serialization.Serializable

@Serializable
data class ClientSettings(
    val name: String,
    val active: Boolean = true,
    val features: Set<String> = allFeatures,
    val supportEmail: String? = null,
    val passwordLogin: Boolean = true,
    val otpLogin: Boolean = false,
)

@Serializable
data class Location(
    val name: String,
    val kind: String = "condominium",
    val timeZone: String = "America/Sao_Paulo",
    val features: Set<String> = allFeatures,
    val active: Boolean = true,
    val address: String? = null,
    val operatingRules: String? = null,
    val petRules: PetRules = PetRules(),
)

@Serializable
data class UnitData(val name: String, val building: String? = null, val floor: String? = null)

@Serializable
data class Brand(
    val name: String,
    val application: String,
    val primaryColor: String = "#2563EB",
    val logoUrl: String? = null,
    val supportEmail: String? = null,
    val features: Set<String> = allFeatures,
)

@Serializable
data class InvitationRequest(
    val email: String,
    val name: String,
    val locationId: String? = null,
    val unitId: String? = null,
    val role: String = "resident",
    val expiresAt: String? = null,
)

@Serializable
data class AccountState(val active: Boolean)

@Serializable
data class AccountSummary(val id: String, val email: String, val name: String, val active: Boolean)

@Serializable
data class Report(val counts: Map<String, Int>, val statuses: Map<String, Map<String, Int>>)
