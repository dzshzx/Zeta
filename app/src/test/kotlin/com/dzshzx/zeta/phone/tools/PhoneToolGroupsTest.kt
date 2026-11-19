package com.dzshzx.zeta.phone.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolGroupsTest {
    private val names = PhoneToolGroups.all.flatMap { group -> group.tools.map { (it as JsonObject)["name"]!!.jsonPrimitive.content } }

    /** 登记表按名字查组，重名会被后来的悄悄盖掉。 */
    @Test
    fun toolNamesAreUniqueAcrossGroups() {
        assertEquals(names.groupBy { it }.filterValues { it.size > 1 }.keys, emptySet<String>())
    }

    @Test
    fun everyToolIsFoundByItsName() {
        assertTrue(names.isNotEmpty())
        for (name in names) assertTrue(name, PhoneToolGroups.find(name) != null)
    }
}
