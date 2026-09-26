package com.community.api.identity

import com.community.api.core.*
import java.time.Instant
import kotlin.test.*

class IdentityChangesTest {
    @Test
    fun `password change verifies current password and revokes pending recovery`() = Database.memory().use { db ->
        db.seedIdentity()
        val token = db.signIn()
        val actor = db.tx { it.authenticate(token.accessToken) }
        db.tx { it.requestRecovery(EmailRequest(tenantA, testEmail), "test-host", false) }.unwrap()
        assertFailsWith<ApiException> {
            db.tx { it.changePassword(actor, PasswordRequest("incorrect", testPassword), "test-host") }.unwrap()
        }
        db.tx { it.authenticate(token.accessToken) }
        db.tx { it.changePassword(actor, PasswordRequest(testPassword, "updated-password-593"), "test-host") }.unwrap()
        assertFailsWith<ApiException> { db.tx { it.authenticate(token.accessToken) } }
        assertTrue(db.tx { it.list("auth_delivery", tenantA).isEmpty() })
