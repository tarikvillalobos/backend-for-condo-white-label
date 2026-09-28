package com.community.api.v1

import com.community.api.core.Database
import com.community.api.platform.ClientSettings
import com.community.api.core.Membership
import com.community.api.core.body
import com.community.api.identity.createAccount
import com.community.api.module
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class V1HttpRoutingTest {
    @Test fun signedFileErrorsUseProblemDocument() = testApplication {
        val db = Database.memory()
        application { module(db, enableLegacyApi = false) }
        val response = client.get("/v1/files/00000000-0000-0000-0000-000000000000")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.headers["Content-Type"]!!.startsWith("application/problem+json"))
        val body = com.community.api.core.json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ACCESS_DENIED", body["code"]!!.jsonPrimitive.content)
    }

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
        val login = client.post("/v1/auth/password/login") {
            header("X-Brand-Id",brand); header("Content-Type","application/json")
            setBody(obj("identifier" to "admin@demo.test","password" to "TestingPassphrase123!").toString())
        }
        assertEquals(HttpStatusCode.OK,login.status)
        val token = com.community.api.core.json.parseToJsonElement(login.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
        val modules = Contract.schemas["Modules"]!!.jsonObject["properties"]!!.jsonObject.keys.associateWith { true }
        repeat(2) { index ->
            val created = client.post("/v1/admin/condominiums") {
                header("X-Brand-Id",brand); header("Authorization","Bearer $token")
                header("Idempotency-Key",java.util.UUID.randomUUID().toString()); header("Content-Type","application/json")
                setBody(obj("name" to "Condo $index","address" to "Rua $index","timeZone" to "UTC","modules" to modules).toString())
            }
            assertEquals(HttpStatusCode.Created,created.status)
        }
        val first = client.get("/v1/admin/condominiums?limit=1") {
            header("X-Brand-Id",brand);header("Authorization","Bearer $token")
        }
        assertEquals(HttpStatusCode.OK,first.status)
        val firstBody = com.community.api.core.json.parseToJsonElement(first.bodyAsText()).jsonObject
        val cursor = firstBody["page"]!!.jsonObject["nextCursor"]!!.jsonPrimitive.content
        val next = client.get("/v1/admin/condominiums?limit=1&cursor=${java.net.URLEncoder.encode(cursor,"UTF-8")}") {
            header("X-Brand-Id",brand);header("Authorization","Bearer $token")
        }
        assertEquals(HttpStatusCode.OK,next.status)
        val nextBody = com.community.api.core.json.parseToJsonElement(next.bodyAsText()).jsonObject
        assertNotEquals(firstBody["items"]!!.jsonArray[0].jsonObject["id"],nextBody["items"]!!.jsonArray[0].jsonObject["id"])
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
