package com.community.api.v1.platform

import com.community.api.core.*
import com.community.api.v1.*
import com.community.api.v1.identity.*
import kotlinx.serialization.json.*
import kotlin.test.*

class PlatformContractTest {
    @Test fun `condominium creation seeds valid types and a single root`() = PlatformFixture().use { f ->
        val created = f.createCondo()
        Contract.validate(Contract.schemas.getValue("CondominiumCreated").jsonObject, created)
        assertEquals(9, created["nodeTypes"]!!.jsonArray.size)
        val root = f.invoke("adminGetStructure").body.jsonObject
        Contract.validate(Contract.schemas.getValue("StructureNode").jsonObject, root)
        assertEquals(created["rootNodeId"], root["id"])
    }

    @Test fun `structure rejects duplicate sibling labels and cyclic moves`() = PlatformFixture().use { f ->
        val created = f.createCondo()
        val type = created["nodeTypes"]!!.jsonArray.first { it.jsonObject.string("code") == "block" }.jsonObject
        val input = obj("typeId" to type["id"], "parentId" to created["rootNodeId"], "label" to "Bloco A")
        val block = f.invoke("createNode", input).body.jsonObject
        assertEquals(409, assertFailsWith<ApiException> { f.invoke("createNode", input) }.status)
        assertEquals(422, assertFailsWith<ApiException> {
            f.invoke("moveNode", obj("newParentId" to block["id"]), mapOf("nodeId" to block.string("id")!!))
        }.status)
    }

    @Test fun `pre registration invitation activates the existing pending identity and membership`() = PlatformFixture().use { f ->
        val created = f.createCondo()
        val type = created["nodeTypes"]!!.jsonArray.first { it.jsonObject.string("code") == "unit" }.jsonObject
        val unit = f.invoke("createNode", obj("typeId" to type["id"], "parentId" to created["rootNodeId"], "label" to "101")).body.jsonObject
        val registered = f.invoke("adminCreateMembership", obj("nodeId" to unit["id"], "role" to "resident",
            "person" to obj("name" to "New Resident", "cpf" to "11144477735", "email" to "new@example.test"))).body.jsonObject
        Contract.validate(Contract.schemas.getValue("MembershipAdminCreated").jsonObject, registered)
        val membership = registered["membership"]!!.jsonObject
        assertEquals("pending", membership.string("status"))
        val code = registered["invitation"]!!.jsonObject.string("code")!!
        val accepted = f.identity.invoke("acceptInvitation", obj("name" to "New Resident", "cpf" to "11144477735",
