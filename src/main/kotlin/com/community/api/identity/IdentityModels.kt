package com.community.api.identity

import kotlinx.serialization.Serializable

@Serializable
data class Account(val email: String, val name: String, val passwordHash: String, val active: Boolean = true)

@Serializable
data class Profile(val id: String, val tenantId: String, val email: String, val name: String)

@Serializable
data class Tokens(val accessToken: String, val refreshToken: String, val expiresIn: Int = 900, val tokenType: String = "Bearer")

@Serializable
data class InvitationIssue(val userId: String, val token: String, val expiresAt: String)

@Serializable
data class SessionView(val id: String, val device: String, val createdAt: String, val expiresAt: String, val current: Boolean)

@Serializable
internal data class SessionData(
    val accessHash: String, val refreshHash: String, val accessExpiresAt: String, val expiresAt: String,
    val device: String, val revoked: Boolean = false, val usedRefreshHashes: List<String> = emptyList(),
    val verifiedAt: String? = null,
)

@Serializable
internal data class ChallengeData(
    val type: String, val secretHash: String, val expiresAt: String, val attempts: Int = 0,
    val consumed: Boolean = false, val newEmail: String? = null,
)

@Serializable
internal data class AuthDelivery(
    val type: String, val email: String, val credential: String, val expiresAt: String,
    val attempts: Int = 0, val nextAttemptAt: String? = null, val leaseId: String? = null,
    val leaseUntil: String? = null, val lastFailure: String? = null, val status: String = "pending",
)

@Serializable
