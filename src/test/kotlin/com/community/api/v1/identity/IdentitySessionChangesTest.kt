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
        val current = f.login().string("accessToken")!!
        val other = f.login().string("accessToken")!!
        assertEquals(422, assertFailsWith<ApiException> { f.invoke("verifyStepUp", token = current) }.status)
        val (challenge, otp) = f.challenge("step_up", current)
        val input = obj("challengeId" to challenge["id"], "code" to otp)
        assertEquals(404, assertFailsWith<ApiException> { f.invoke("verifyStepUp", input, other) }.status)
        val verified = f.invoke("verifyStepUp", input, current)
        assertEquals(200, verified.status)
        Contract.validate(Contract.schemas.getValue("StepUpResult").jsonObject, verified.body)
    }

    @Test fun `contact OTP cannot create a login session`() = IdentityFixture().use { f ->
        val current = f.login().string("accessToken")!!
        val (challenge, otp) = f.challenge("contact_change", current)
        val mismatch = assertFailsWith<ApiException> {
            f.invoke("verifyLoginChallenge", obj("code" to otp), path = mapOf("challengeId" to challenge.string("id")!!))
        }
        assertEquals(404, mismatch.status)
    }

    @Test fun `logout revokes access refresh and registered push recipient`() = IdentityFixture().use { f ->
        val tokens = f.login()
        val current = tokens.string("accessToken")!!
        val id = java.util.UUID.randomUUID().toString()
        val key = Secrets.token()
        val input = obj("platform" to "android", "provider" to "fcm", "token" to "provider-registration-token",
            "permission" to "authorized", "appVersion" to "1.0.0")
        val path = mapOf("installationId" to id)
        val headers = mapOf("X-Installation-Key" to key)
        assertEquals(200, f.invoke("registerPushDevice", input, current, path, headers).status)
        assertEquals(204, f.invoke("logoutSession", token = current).status)
        assertEquals(401, f.invoke("refreshSession", obj("refreshToken" to tokens["refreshToken"])).status)
        f.db.tx { tx ->
            val registration = V1Store(tx, tenant, brand).get("push_registration", id)
            assertEquals("inactive", registration.data.string("status"))
            assertNull(registration.data.string("tokenEncrypted"))
            assertNotNull(V1Store(tx, tenant, brand).find("installation", id))
        }
    }

