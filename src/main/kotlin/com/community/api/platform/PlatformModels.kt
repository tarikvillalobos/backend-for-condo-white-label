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
