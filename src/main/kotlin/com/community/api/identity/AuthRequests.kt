package com.community.api.identity

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val tenantId: String, val email: String, val password: String, val device: String = "Unknown device")

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class EmailRequest(val tenantId: String, val email: String)

@Serializable
data class ActivationRequest(val token: String, val password: String)

@Serializable
data class OtpRequest(val tenantId: String, val email: String, val code: String, val device: String = "Unknown device")

@Serializable
data class PasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class ProfileRequest(val name: String)

@Serializable
data class ContactRequest(val email: String, val password: String)

@Serializable
data class ContactConfirmation(val token: String)

@Serializable
data class VerificationRequest(val password: String)
