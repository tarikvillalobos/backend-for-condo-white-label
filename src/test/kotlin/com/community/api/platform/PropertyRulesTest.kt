package com.community.api.platform

import com.community.api.community.PetInput
import com.community.api.community.enforcePetRules
import com.community.api.core.*
import kotlin.test.*

class PropertyRulesTest {
    @Test
    fun `ownership relationship does not grant administrative permissions`() {
        val f = PlatformFixture()
        f.db.use { db -> db.tx { tx ->
            val unit = tx.create("unit", f.tenant, f.location, data = body(UnitData("101")))
            tx.saveMembership(Context(f.actor, f.location, setOf("*")), Membership(f.resident, f.location, unit.id, relationship = "owner"))
            val actor = Actor(f.resident, f.tenant, "test")
            assertFalse(tx.authorize(actor, f.location, "packages.read.own").can("memberships.manage"))
            assertFailsWith<ApiException> { tx.authorize(actor, f.location, "packages.read.all") }
            assertFailsWith<ApiException> {
                tx.validateMembership(Context(f.actor, f.location, setOf("*")), Membership(f.resident, f.location, relationship = "owner"))
            }
