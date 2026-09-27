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

