package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AuthenticationContractTest {
    @Test fun `password login returns contract tokens and isolates the brand`() = IdentityFixture().use { f ->
        val tokens = f.login()
        Contract.validate(Contract.schemas.getValue("SessionTokens").jsonObject, tokens)
        val profile = f.invoke("getProfile", token = tokens.string("accessToken"))
        Contract.validate(Contract.schemas.getValue("Profile").jsonObject, profile.body)
        val wrongBrand = assertFailsWith<ApiException> {
            f.invoke("getProfile", token = tokens.string("accessToken"), brandId = "another-brand")
        }
        assertEquals(401, wrongBrand.status)
        assertEquals(401, f.invoke("loginWithPassword", obj("identifier" to email, "password" to "incorrect")).status)
    }

    @Test fun `refresh rotation revokes replayed token family`() = IdentityFixture().use { f ->
        val original = f.login()
        val next = f.invoke("refreshSession", obj("refreshToken" to original["refreshToken"]))
        assertEquals(200, next.status)
        assertNotEquals(original["refreshToken"], next.body.jsonObject["refreshToken"])
        assertEquals(401, f.invoke("refreshSession", obj("refreshToken" to original["refreshToken"])).status)
        assertEquals(401, assertFailsWith<ApiException> {
            f.invoke("getProfile", token = next.body.jsonObject.string("accessToken"))
        }.status)
    }

    @Test fun `OTP is encrypted in the outbox consumed once and never returned`() = IdentityFixture().use { f ->
        val (challenge, otp) = f.challenge()
        assertFalse(challenge.toString().contains(otp))
        val input = obj("code" to otp)
        val path = mapOf("challengeId" to challenge.string("id")!!)
        val tokens = f.invoke("verifyLoginChallenge", input, path = path)
        assertEquals(200, tokens.status)
        assertEquals(410, assertFailsWith<ApiException> { f.invoke("verifyLoginChallenge", input, path = path) }.status)
        val profile = f.invoke("getProfile", token = tokens.body.jsonObject.string("accessToken")).body.jsonObject
