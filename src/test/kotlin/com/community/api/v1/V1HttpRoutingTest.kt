package com.community.api.v1

import com.community.api.core.Database
import com.community.api.platform.ClientSettings
import com.community.api.core.Membership
import com.community.api.core.body
import com.community.api.identity.createAccount
import com.community.api.module
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V1HttpRoutingTest {
    @Test fun bootstrappedBrandServesItsConfiguration() = testApplication {
        val db = Database.memory()
        val brand = "11111111-1111-4111-8111-111111111111"
        db.tx { tx ->
            tx.create("client",brand,data=body(ClientSettings("Demo")),id=brand)
            val account = tx.createAccount(brand,"admin@demo.test","TestingPassphrase123!","Administrator")
            tx.create("membership",brand,ownerId=account.id,data=body(Membership(account.id,role="client_admin")))
        }
        application { module(db,enableLegacyApi=false) }
        val response = client.get("/v1/configuration") { header("X-Brand-Id",brand) }
        assertEquals(HttpStatusCode.OK,response.status)
        assertEquals(brand,com.community.api.core.json.parseToJsonElement(response.bodyAsText()).jsonObject["brandId"]!!.jsonPrimitive.content)
    }

    @Test fun brandLookupErrorUsesProblemDocument() = testApplication {
        val db = Database.memory()
        application { module(db, enableLegacyApi = false) }
        val response = client.get("/v1/configuration") {
            header("X-Brand-Id", "00000000-0000-0000-0000-000000000000")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.headers["Content-Type"]!!.startsWith("application/problem+json"))
        assertEquals("RESOURCE_NOT_FOUND", com.community.api.core.json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
    }
}
