package com.community.api.v1.platform

import com.community.api.core.*
import com.community.api.v1.*
import com.community.api.v1.identity.*
import kotlinx.serialization.json.*
import kotlin.test.*

class PlatformAuthorizationTest {
    @Test fun `resident role cannot receive brand administrative permissions`() = PlatformFixture().use { f ->
        val denied = assertFailsWith<ApiException> {
            f.invoke("updateRolePermissions", obj("permissions" to listOf("brand.settings")), mapOf("role" to "resident"))
        }
        assertEquals(422, denied.status)
    }

    @Test fun `custom role cannot exceed its base role`() = PlatformFixture().use { f ->
        val organization = f.invoke("createOrganization", obj("kind" to "property_manager", "name" to "Administradora")).body.jsonObject
        val denied = assertFailsWith<ApiException> {
            f.invoke("createCustomRole", obj("code" to "unsafe_role", "name" to "Unsafe", "baseRole" to "porter",
                "permissions" to listOf("brand.users")), mapOf("organizationId" to organization.string("id")!!))
        }
        assertEquals("CUSTOM_ROLE_EXCEEDS_BASE", denied.code)
    }

    @Test fun `failed bulk creation rolls back earlier valid nodes`() = PlatformFixture().use { f ->
        f.createCondo()
        val denied = assertFailsWith<ApiException> {
            f.invoke("createNodesBulk", obj("nodes" to listOf(
                obj("ref" to "valid", "typeCode" to "unit", "label" to "101"),
                obj("ref" to "bad", "parentRef" to "missing", "typeCode" to "unit", "label" to "102"))))
        }
        assertEquals("INVALID_PARENT_REF", denied.code)
        f.identity.db.tx { tx -> assertEquals(1, V1Store(tx, tenant, brand).list("node", f.condoId).size) }
    }

    @Test fun `node restore preserves hierarchy and rejects conflicting sibling labels`() = PlatformFixture().use { f ->
        val condo = f.createCondo()
        val type = condo["nodeTypes"]!!.jsonArray.first { it.jsonObject.string("code") == "unit" }.jsonObject
        val input = obj("typeId" to type["id"], "parentId" to condo["rootNodeId"], "label" to "101")
        val old = f.invoke("createNode", input).body.jsonObject
        val path = mapOf("nodeId" to old.string("id")!!)
        val removed = f.invoke("deleteNode", path = path)
        Contract.validate(Contract.schemas.getValue("NodeDeactivation").jsonObject, removed.body)
        f.invoke("createNode", input)
        assertEquals(409, assertFailsWith<ApiException> { f.invoke("restoreNode", path = path) }.status)
    }

    @Test fun `brand block revokes only sessions for its brand`() = PlatformFixture().use { f ->
        val operator = f.identity.db.tx { tx ->
            val user = tx.create("account", tenant, data = body(com.community.api.identity.Account("operator@example.test",
                "Operator", com.community.api.identity.Passwords.hash(password))))
            indexIdentityAccount(tx, user)
            user
        }
        val tokens = f.identity.invoke("loginWithPassword", obj("identifier" to "operator@example.test", "password" to password)).body.jsonObject
        f.invoke("updateAccountState", obj("status" to "blocked", "reason" to "Administrative test"), mapOf("userId" to operator.id))
        assertEquals(401, assertFailsWith<ApiException> {
            f.identity.invoke("getProfile", token = tokens.string("accessToken"))
        }.status)
