package com.community.api.community

import com.community.api.core.*
import com.community.api.identity.Account
import com.community.api.identity.issueSession
import com.community.api.plugins.configureHttp
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.routing.routing
import kotlinx.serialization.json.*
import java.util.UUID

internal data class TestIdentity(val id: String, val token: String)
internal class CommunityFixture : AutoCloseable {
    val db = Database.memory()
    val tenant = UUID.randomUUID().toString()
    val location = UUID.randomUUID().toString()
    val unit = UUID.randomUUID().toString()
    val otherUnit = UUID.randomUUID().toString()
    init {
        db.tx { tx ->
            tx.create("client", tenant, data = buildJsonObject { put("active", true); put("features", json.encodeToJsonElement(allFeatures)) }, id = tenant)
            tx.create("location", tenant, data = buildJsonObject {
                put("name", "Community"); put("active", true); put("features", json.encodeToJsonElement(allFeatures)); put("timeZone", "America/Sao_Paulo")
            }, id = location)
            tx.create("unit", tenant, location, data = buildJsonObject { put("name", "101") }, id = unit)
            tx.create("unit", tenant, location, data = buildJsonObject { put("name", "102") }, id = otherUnit)
        }
    }
    val resident = identity("resident", unit)
    val other = identity("resident", otherUnit)
    val manager = identity("property_manager", null)
    private fun identity(role: String, unitId: String?): TestIdentity = db.tx { tx ->
        val id = UUID.randomUUID().toString()
        val account = tx.create("account", tenant, data = body(Account("$id@example.test", role, "unused")), id = id)
        tx.create("membership", tenant, location, id, body(Membership(id, location, unitId, role)))
        TestIdentity(id, tx.issueSession(account, "test").accessToken)
    }
    fun install(builder: ApplicationTestBuilder) = builder.application {
        configureHttp()
        routing { communityRoutes(db) }
    }
    fun path(resource: String) = "/api/v1/locations/$location/$resource"
    override fun close() = db.close()
}
