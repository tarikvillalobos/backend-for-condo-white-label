package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.identity.Account
import com.community.api.v1.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.test.*

class PrivacyContractTest {
    @Test fun `privacy defaults are explicit opt out and updates preserve other consents`() = IdentityFixture().use { f ->
        val token = f.login().string("accessToken")!!
        val original = f.invoke("getPrivacy", token = token).body.jsonObject
        assertEquals(JsonPrimitive(false), original["marketingConsent"])
        val changed = f.invoke("updatePrivacy", obj("cameraAccessConsent" to true), token).body.jsonObject
        assertEquals(JsonPrimitive(true), changed["cameraAccessConsent"])
        assertEquals(JsonPrimitive(false), changed["marketingConsent"])
        Contract.validate(Contract.schemas.getValue("PrivacySettings").jsonObject, changed)
    }

    @Test fun `account deletion requires OTP even after password authentication`() = IdentityFixture().use { f ->
        val token = f.login().string("accessToken")!!
        assertEquals("OTP_VERIFICATION_REQUIRED", assertFailsWith<ApiException> {
            f.invoke("createDataRequest", obj("kind" to "deletion"), token)
        }.code)
        val (challenge, otp) = f.challenge("step_up", token)
        f.invoke("verifyStepUp", obj("challengeId" to challenge["id"], "code" to otp), token)
        val requested = f.invoke("createDataRequest", obj("kind" to "deletion"), token)
        assertEquals(202, requested.status)
        assertEquals("received", requested.body.jsonObject.string("status"))
        assertEquals(0, runBlocking { processIdentityDataRequests(f.db) })
    }

    @Test fun `deletion worker observes grace period and anonymizes after it expires`() = IdentityFixture().use { f ->
        f.login()
        f.db.tx { tx ->
            val store = V1Store(tx, tenant, brand)
            store.create("data_request", obj("kind" to "deletion", "status" to "received",
                "requestedAt" to Instant.now().minusSeconds(86400), "executeAfter" to Instant.now().minusSeconds(1)), ownerId = f.user.id)
