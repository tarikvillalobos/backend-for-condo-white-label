package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.identity.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.util.UUID

internal const val tenant = "v1-identity-tenant"
internal const val brand = "v1-identity-brand"
internal const val password = "a-strong-identity-password-123"
internal const val email = "identity@example.test"
private val passwordHash by lazy { Passwords.hash(password) }

internal class IdentityFixture : AutoCloseable {
    val db = Database.memory()
    val user: Record = db.tx { tx ->
        tx.create("client", tenant, id = tenant, data = obj("active" to true, "name" to "Identity tenant"))
        val user = tx.create("account", tenant, data = body(Account(email, "Identity Test", passwordHash)))
        indexIdentityAccount(tx, user, "52998224725")
