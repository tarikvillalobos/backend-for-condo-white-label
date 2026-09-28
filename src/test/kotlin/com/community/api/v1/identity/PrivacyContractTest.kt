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
