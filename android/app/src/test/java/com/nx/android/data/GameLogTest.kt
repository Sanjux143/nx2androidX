package com.nx.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * The game's runtime log: the host's log in the data pack, the one of the run before it, and the bundle
 * that goes out to another app (the device, the game and the logs).
 */
class GameLogTest {

    private fun tmp(): File = Files.createTempDirectory("nx2a-log").toFile()

    /** a pack whose game has run: the host's log is in it */
    private fun packWithLog(body: String): File {
        val pack = tmp()
        File(pack, "log.txt").writeText(body)
        return pack
    }

    /** the entries of a bundle: its name and what is in it */
    private fun entries(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().toList().associate { it.name to z.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun theLogIsInThePack() {
        val pack = tmp()
        assertNull("a game that never ran has no log", GameLog.inPack(pack))
        val log = File(pack, "log.txt")
        log.writeText("[dm] NX2A host on Android\n")
        assertEquals(log, GameLog.inPack(pack))
    }

    @Test
    fun theRunBeforeTheLastOneIsThereToo() {
        val pack = tmp()
        assertNull("a game that ran once has no previous log", GameLog.previousInPack(pack))
        File(pack, "log.txt").writeText("[dm] the second run\n")
        val prev = File(pack, "log.prev.txt")
        prev.writeText("[dm] the first run\n")
        assertEquals(prev, GameLog.previousInPack(pack))
        assertEquals(listOf("log.txt" to File(pack, "log.txt"), "log.prev.txt" to prev), GameLog.logs(pack))
    }

    @Test
    fun aGameThatNeverRanHasNoLogToShare() {
        val dest = tmp()
        assertNull(GameLog.bundle("device: test\n", "Hades", "game: Hades\n", tmp(), dest))
        assertEquals("nothing is written", 0, dest.listFiles()?.size ?: 0)
    }

    @Test
    fun theBundleHoldsTheDeviceTheGameAndBothLogs() {
        val pack = packWithLog("[dm] the last run\n")
        File(pack, "log.prev.txt").writeText("[dm] the run before it\n")
        val dest = tmp()
        val zip = GameLog.bundle("phone: Xiaomi 23117RK66C\n", "Hades", GameLog.facts("Hades", "0100000000010000", "1.0.0", "playable", 3), pack, dest)
        assertNotNull(zip)
        assertEquals(File(dest, "Hades-log.zip"), zip)
        val in_ = entries(zip!!)
        assertTrue("the device's facts are in it: ${in_.keys}", in_.containsKey("device.txt"))
        assertTrue("the device's facts are in it", in_["device.txt"]!!.contains("Xiaomi 23117RK66C"))
        assertTrue("the game's facts are in it: ${in_.keys}", in_.containsKey("game.txt"))
        assertTrue("the game's facts are in it", in_["game.txt"]!!.contains("title id: 0100000000010000"))
        assertTrue("the last run is in it: ${in_.keys}", in_.containsKey("log.txt"))
        assertTrue("the last run is in it", in_["log.txt"]!!.contains("the last run"))
        assertTrue("the run before it is in it: ${in_.keys}", in_.containsKey("log.prev.txt"))
        assertTrue("the run before it is in it", in_["log.prev.txt"]!!.contains("the run before it"))
    }

    @Test
    fun aGameWithoutAPreviousRunStillSharesItsLastOne() {
        val pack = packWithLog("[dm] the first run\n")
        val dest = tmp()
        val zip = GameLog.bundle("device: test\n", "Hades", "game: Hades\n", pack, dest)
        assertNotNull(zip)
        val in_ = entries(zip!!)
        assertTrue("the last run is in it: ${in_.keys}", in_.containsKey("log.txt"))
        assertTrue("there is no previous run in it: ${in_.keys}", !in_.containsKey("log.prev.txt"))
    }

    @Test
    fun theNameIsSafeInAFileName() {
        assertEquals("Hades-log.zip", GameLog.shareName("Hades"))
        assertEquals("Super_Mario_Odyssey-log.zip", GameLog.shareName("Super Mario Odyssey"))
        assertEquals("A_B-log.zip", GameLog.shareName("A/B"))
        assertEquals("game-log.zip", GameLog.shareName(""))
    }

    @Test
    fun anOlderBundleIsReplaced() {
        val pack = packWithLog("[dm] the second run\n")
        val dest = tmp()
        File(dest, "Hades-log.zip").writeText("[dm] the first run\n")
        val zip = GameLog.bundle("device: test\n", "Hades", "game: Hades\n", pack, dest)
        assertNotNull(zip)
        assertTrue("the new bundle is there", entries(zip!!)["log.txt"]!!.contains("the second run"))
        assertEquals("only the bundle is in the folder", 1, dest.listFiles()?.size ?: 0)
    }

    @Test
    fun theBundleGoesIntoAFolderThatIsNotThereYet() {
        val pack = packWithLog("[dm] hello\n")
        val dest = File(tmp(), "nx2android")
        val zip = GameLog.bundle("device: test\n", "Hades", "game: Hades\n", pack, dest)
        assertNotNull(zip)
        assertTrue(dest.isDirectory)
        assertTrue(zip!!.isFile)
    }

    @Test
    fun theGamesFactsSayWhoItIs() {
        val facts = GameLog.facts("歧路旅人0", "01001300232F4000", "1.0.5", "playable", 3)
        assertTrue(facts.contains("game: 歧路旅人0"))
        assertTrue(facts.contains("title id: 01001300232F4000"))
        assertTrue(facts.contains("version: 1.0.5"))
        assertTrue(facts.contains("profile status: playable"))
    }
}
