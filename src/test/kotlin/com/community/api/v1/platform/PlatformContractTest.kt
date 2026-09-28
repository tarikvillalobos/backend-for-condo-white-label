package com.community.api.v1.platform

import com.community.api.core.*
import com.community.api.v1.*
import com.community.api.v1.identity.*
import kotlinx.serialization.json.*
import kotlin.test.*

class PlatformContractTest {
    @Test fun `condominium creation seeds valid types and a single root`() = PlatformFixture().use { f ->
        val created = f.createCondo()
        Contract.validate(Contract.schemas.getValue("CondominiumCreated").jsonObject, created)
        assertEquals(9, created["nodeTypes"]!!.jsonArray.size)
        val root = f.invoke("adminGetStructure").body.jsonObject
        Contract.validate(Contract.schemas.getValue("StructureNode").jsonObject, root)
        assertEquals(created["rootNodeId"], root["id"])
    }

    @Test fun `structure rejects duplicate sibling labels and cyclic moves`() = PlatformFixture().use { f ->
        val created = f.createCondo()
