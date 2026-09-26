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
