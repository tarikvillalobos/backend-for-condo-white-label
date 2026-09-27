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
        user
    }

    fun context(tx: Tx, operation: String = "test", input: JsonObject = obj(), token: String? = null,
        path: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap(), brandId: String = brand): V1Context {
        val actor = token?.let { authenticateV1Session(tx, tenant, brandId, it) }
        return V1Context(tx, operation, tenant, brandId, UUID.randomUUID().toString(), input, path,
            headers = headers + ("X-Remote-Host" to "fixture-host"), principal = actor?.let { V1Principal(actor = it) })
    }

    fun invoke(operation: String, input: JsonObject = obj(), token: String? = null,
        path: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap(), brandId: String = brand): V1Response =
        db.tx { identityHandlers().getValue(operation).handle(context(it, operation, input, token, path, headers, brandId)) }

    fun login(): JsonObject = invoke("loginWithPassword", obj("identifier" to email, "password" to password)).body.jsonObject

    fun challenge(purpose: String = "login", token: String? = null): Pair<JsonObject, String> = db.tx { tx ->
        val context = context(tx, token = token)
        val challenge = context.issueIdentityChallenge(purpose, user, email, context.principal?.sessionId)
        val id = challenge.string("id")!!
        val delivery = tx.list("auth_delivery", tenant, ownerId = id).single().decode<AuthDelivery>()
        challenge to Secrets.unseal(delivery.credential.removePrefix("sealed:"))
    }

    override fun close() = db.close()
}
