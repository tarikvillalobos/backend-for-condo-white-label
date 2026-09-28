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
            "email" to "new@example.test", "password" to "new-resident-password-123", "acceptedTermsVersion" to "1"), path = mapOf("code" to code))
        assertEquals(201, accepted.status)
        assertEquals(membership["user"]!!.jsonObject["id"], accepted.body.jsonObject["userId"])
        assertEquals("active", f.invoke("adminGetMembership", path = mapOf("membershipId" to membership.string("id")!!)).body.jsonObject.string("status"))
        assertEquals(409, assertFailsWith<ApiException> { f.invoke("deleteNode", path = mapOf("nodeId" to unit.string("id")!!)) }.status)
    }

    @Test fun `membership import dry run writes no accounts or memberships`() = PlatformFixture().use { f ->
        val created = f.createCondo()
        val type = created["nodeTypes"]!!.jsonArray.first { it.jsonObject.string("code") == "unit" }.jsonObject
        val unit = f.invoke("createNode", obj("typeId" to type["id"], "parentId" to created["rootNodeId"], "label" to "101")).body.jsonObject
        val result = f.invoke("adminImportMemberships", obj("dryRun" to true, "rows" to listOf(obj("rowRef" to "1",
            "nodeId" to unit["id"], "role" to "resident", "person" to obj("name" to "Import Test", "email" to "import@example.test")))))
        assertEquals(1, result.body.jsonObject["created"]!!.jsonPrimitive.int)
        f.identity.db.tx { tx ->
            assertEquals(1, tx.list("account", tenant).size)
            assertTrue(V1Store(tx, tenant, brand).list("membership").isEmpty())
        }
    }
}

internal class PlatformFixture : AutoCloseable {
    val identity = IdentityFixture()
    var condoId: String? = null
    init {
        identity.db.tx { tx ->
            val store = V1Store(tx, tenant, brand)
            val modules = obj(*Contract.schemas["Modules"]!!.jsonObject["properties"]!!.jsonObject.keys.map { it to true }.toTypedArray())
            store.create("brand", obj("name" to "Test Brand", "modules" to modules, "termsVersion" to "1"), id = brand)
            store.create("staff_assignment", obj("brandId" to brand, "role" to "brand_admin", "status" to "active",
                "scope" to "brand", "permissions" to listOf("*"), "mfaRequired" to false), ownerId = identity.user.id)
        }
    }
    fun invoke(operation: String, input: JsonObject = obj(), path: Map<String, String> = emptyMap()): V1Response = identity.db.tx { tx ->
        val c = V1Context(tx, operation, tenant, brand, java.util.UUID.randomUUID().toString(), input,
            path + condoId?.let { mapOf("condominiumId" to it) }.orEmpty(),
            principal = V1Principal(actor = Actor(identity.user.id, tenant, "test-session"), staff = true, permissions = setOf("*")))
        platformHandlers().getValue(operation).handle(c)
    }
    fun createCondo(): JsonObject {
