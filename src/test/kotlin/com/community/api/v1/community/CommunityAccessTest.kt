package com.community.api.v1.community

import com.community.api.core.ApiException
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.*

class CommunityAccessTest {
    @Test fun `single use invitation consumes credential and exit remains possible`() = CommunityFixture().use { f ->
        val gate = f.run("adminCreateGate", obj("name" to "Portaria", "kind" to "pedestrian"), staff = true)
        val visitor = f.run("createVisitor", obj("name" to "Visitante", "kind" to "visitor", "document" to "12345678900"))
        assertEquals("***8900", visitor.body.jsonObject["document"]!!.jsonPrimitive.content)
        val invite = f.run("createAccessInvite", obj("visitorId" to visitor.id(), "validFrom" to future(-60),
            "validUntil" to future(3600), "singleUse" to true, "allowedGates" to JsonArray(listOf(JsonPrimitive(gate.id())))))
        val credential = f.run("getAccessCredential", ids = mapOf("inviteId" to invite.id())).body.jsonObject
        val body = obj("code" to credential["code"], "direction" to "entry", "gateId" to gate.id())
        val first = f.run("validateAccessCredential", body, staff = true).body.jsonObject
        assertTrue(first["valid"]!!.jsonPrimitive.boolean)
        assertTrue(first["consumedNow"]!!.jsonPrimitive.boolean)
        assertFalse(f.run("validateAccessCredential", body, staff = true).body.jsonObject["valid"]!!.jsonPrimitive.boolean)
        val exit = f.run("validateAccessCredential", body.merge(obj("direction" to "exit")), staff = true).body.jsonObject
        assertTrue(exit["valid"]!!.jsonPrimitive.boolean)
        assertFalse(exit["consumedNow"]!!.jsonPrimitive.boolean)
        assertEquals(409, assertFailsWith<ApiException> { f.run("getAccessCredential", ids = mapOf("inviteId" to invite.id())) }.status)
    }
    @Test fun `visitor deletion revokes active invitations`() = CommunityFixture().use { f ->
        val visitor = f.run("createVisitor", obj("name" to "Visitante", "kind" to "visitor"))
        val invite = f.run("createAccessInvite", obj("visitorId" to visitor.id(), "validFrom" to future(-60), "validUntil" to future(3600), "singleUse" to false))
        f.run("deleteVisitor", ids = mapOf("visitorId" to visitor.id()))
        val result = f.run("getAccessInvite", ids = mapOf("inviteId" to invite.id())).body.jsonObject
        assertEquals("revoked", result["status"]!!.jsonPrimitive.content)
        assertEquals("Visitante", result["visitor"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }
    @Test fun `unknown admission codes are rate limited for each operator`() = CommunityFixture().use { f ->
        val gate = f.run("adminCreateGate", obj("name" to "Portaria", "kind" to "pedestrian"), staff = true)
        val body = obj("code" to "999999", "direction" to "entry", "gateId" to gate.id())
        repeat(10) { assertFalse(f.run("validateAccessCredential", body, staff = true).body.jsonObject["valid"]!!.jsonPrimitive.boolean) }
        assertEquals(429, assertFailsWith<ApiException> { f.run("validateAccessCredential", body, staff = true) }.status)
    }
