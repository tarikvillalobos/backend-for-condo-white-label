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
