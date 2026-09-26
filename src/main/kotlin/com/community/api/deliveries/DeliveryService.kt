package com.community.api.deliveries

import com.community.api.core.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.jsonPrimitive

class DeliveryService(private val clock: Clock = Clock.systemUTC()) {
    private fun Context.location(): String = locationId ?: forbidden()
    private fun Context.allow(permission: String) {
        if (!can(permission)) forbidden()
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

