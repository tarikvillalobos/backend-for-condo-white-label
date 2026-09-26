package com.community.api.identity

import com.community.api.core.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val tenantA = "identity-tenant-a"
internal const val tenantB = "identity-tenant-b"
internal const val testPassword = "long-test-password-137"
internal const val testEmail = "resident@example.com"
internal val testPasswordHash by lazy { Passwords.hash(testPassword) }

internal fun Database.seedIdentity(otp: Boolean = false): Record = tx { tx ->
    for (id in listOf(tenantA, tenantB)) tx.create("client", id, id = id, data = buildJsonObject {
        put("name", "Test client")
        put("active", true)
        put("otpLogin", otp)
    })
    tx.create("account", tenantA, data = body(Account(testEmail, "Resident", testPasswordHash)))
}
