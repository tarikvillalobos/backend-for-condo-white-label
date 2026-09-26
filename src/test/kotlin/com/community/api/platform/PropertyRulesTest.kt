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
        } }
    }

    @Test
    fun `location vaccination species and unit limits apply to create and update`() {
        val f = PlatformFixture()
        f.db.use { db -> db.tx { tx ->
            val location = tx.requireRecord("location", f.location, f.tenant)
            tx.update(location, body(location.decode<Location>().copy(petRules = PetRules(true, 1, setOf("dog")))))
            val ctx = Context(f.actor, f.location, setOf("*"))
            val pet = PetInput("Pet", "dog", "unit", vaccinationUrls = listOf("https://example.test/vaccination.pdf"))
            assertFailsWith<ApiException> { tx.enforcePetRules(ctx, pet.copy(vaccinationUrls = emptyList())) }
            assertFailsWith<ApiException> { tx.enforcePetRules(ctx, pet.copy(species = "cat")) }
            tx.enforcePetRules(ctx, pet)
            val existing = tx.create("pet", f.tenant, f.location, f.admin, body(pet))
            assertEquals(409, assertFailsWith<ApiException> { tx.enforcePetRules(ctx, pet) }.status)
            tx.enforcePetRules(ctx, pet, existing.id)
        } }
    }
}
