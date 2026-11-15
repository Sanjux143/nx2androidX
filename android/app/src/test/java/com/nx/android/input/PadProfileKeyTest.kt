package com.nx.android.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手柄的指纹：同一只手柄在换手机后还是这只；同型号的两只同时插着时分不清，就退化成会话级的。
 */
class PadProfileKeyTest {

    @Test
    fun `the same controller has the same key`() {
        val first = PadProfileKey.resolve("Vendor Pad", 0x1234, 0x5678, listOf(0, 1), deviceId = 3, ambiguous = false)
        val second = PadProfileKey.resolve("Vendor Pad", 0x1234, 0x5678, listOf(0, 1), deviceId = 9, ambiguous = false)
        assertEquals(first.key, second.key)
        assertTrue(first.persistent)
        assertFalse(first.requiresConfirmation)
    }

    @Test
    fun `another model has another key`() {
        val first = PadProfileKey.resolve("Vendor Pad", 0x1234, 0x5678, listOf(0, 1), deviceId = 3, ambiguous = false)
        val second = PadProfileKey.resolve("Other Pad", 0x1234, 0x5678, listOf(0, 1), deviceId = 3, ambiguous = false)
        assertNotEquals(first.key, second.key)
    }

    @Test
    fun `two controllers of the same model are a session of their own`() {
        val identity = PadProfileKey.resolve("Vendor Pad", 0x1234, 0x5678, listOf(0, 1), deviceId = 7, ambiguous = true)
        assertEquals("temporary:7", identity.key)
        assertFalse(identity.persistent)
        assertTrue(identity.requiresConfirmation)
    }

    @Test
    fun `a device without a descriptor is a session of its own`() {
        val identity = PadProfileKey.resolve("", 0, 0, listOf(0), deviceId = 2, ambiguous = false)
        assertEquals("temporary:2", identity.key)
        assertNull(identity.modelTemplateKey)
    }
}
