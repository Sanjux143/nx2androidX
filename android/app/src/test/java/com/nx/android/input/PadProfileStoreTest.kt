package com.nx.android.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 手柄的档案：每只手柄一份，存在 App 自己的目录里；坏了就当没有（回到出厂映射）。
 */
class PadProfileStoreTest {

    private fun store(dir: File) = PadProfileStore(File(dir, PadProfileStore.FILE_NAME))

    @Test
    fun `a profile comes back as it went in`() {
        val dir = Files.createTempDirectory("nx2a-pad").toFile()
        try {
            val store = store(dir)
            val profiles = mapOf(
                "device:abc" to PadProfileStore.Profile(
                    key = "device:abc",
                    name = "Xbox Controller",
                    labels = true,
                    bindings = mapOf(PadSources.scan(305) to "a", PadSources.scan(314) to PadSources.DISABLED),
                    calibration = PadCalibration(deadzone = 0.2f, trigger = 0.6f),
                ),
            )
            store.save(profiles)
            val loaded = store.load().profiles
            val profile = loaded["device:abc"]
            assertNotNull(profile)
            assertEquals("Xbox Controller", profile!!.name)
            assertTrue(profile.labels)
            assertEquals("a", profile.bindings[PadSources.scan(305)])
            assertEquals(PadSources.DISABLED, profile.bindings[PadSources.scan(314)])
            assertEquals(0.2f, profile.calibration.deadzone, 0.0001f)
            assertEquals(0.6f, profile.calibration.trigger, 0.0001f)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a broken file is a file with no profiles, not a crash`() {
        val dir = Files.createTempDirectory("nx2a-pad").toFile()
        try {
            val file = File(dir, PadProfileStore.FILE_NAME)
            file.parentFile?.mkdirs()
            file.writeText("{ this is not json")
            val result = store(dir).load()
            assertTrue(result.profiles.isEmpty())
            assertTrue(result.recoveredFromCorruption)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the backup is used when the file is broken`() {
        val dir = Files.createTempDirectory("nx2a-pad").toFile()
        try {
            val store = store(dir)
            store.save(mapOf("device:abc" to PadProfileStore.Profile(key = "device:abc", labels = true)))
            File(dir, PadProfileStore.FILE_NAME).writeText("broken")
            val result = store.load()
            assertTrue(result.recoveredFromCorruption)
            assertTrue(result.profiles["device:abc"]!!.labels)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `junk in a profile is dropped`() {
        val dir = Files.createTempDirectory("nx2a-pad").toFile()
        try {
            val store = store(dir)
            val raw = """{"schemaVersion":1,"pads":{"device:x":{"labels":true,
                "bindings":{"scan:305":"a","nonsense":"a","scan:999":"nosuchbutton"},
                "disabled":["scan:314","rubbish"]}}}"""
            val profiles = store.decode(raw)
            val bindings = profiles["device:x"]!!.bindings
            assertEquals("a", bindings["scan:305"])
            assertFalse(bindings.containsKey("nonsense"))
            assertFalse(bindings.containsKey("scan:999"))
            assertEquals(PadSources.DISABLED, bindings["scan:314"])
            assertFalse(bindings.containsKey("rubbish"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
