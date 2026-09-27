package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import kotlin.test.*

class IdentitySessionChangesTest {
    @Test fun `password change keeps current session and revokes the others`() = IdentityFixture().use { f ->
        val current = f.login().string("accessToken")!!
        val other = f.login().string("accessToken")!!
        val changed = f.invoke("changePassword", obj("currentPassword" to password,
            "newPassword" to "new-strong-password-for-account"), current)
        assertEquals(204, changed.status)
        assertEquals(200, f.invoke("getProfile", token = current).status)
        assertEquals(401, assertFailsWith<ApiException> { f.invoke("getProfile", token = other) }.status)
        assertEquals(401, f.invoke("loginWithPassword", obj("identifier" to email, "password" to password)).status)
    }

    @Test fun `step up requires exactly one proof and challenge cannot cross sessions`() = IdentityFixture().use { f ->
