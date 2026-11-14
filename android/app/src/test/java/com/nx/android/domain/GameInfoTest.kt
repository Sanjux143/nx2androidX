package com.nx.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameInfoTest {

    @Test
    fun `the porter's report is read out of the guest's output`() {
        val output = """
            [RED] a warning the guest wrote before the report
            {
              "title_id": "0100F2C0115B6000",
              "base": "TOTK.nsp",
              "updates": [
                {
                  "version": "v1.4.0",
                  "file": "TOTK update.nsp",
                  "folder": ""
                }
              ],
              "dlc": [
                {
                  "title_id": "0100F2C0115B6001",
                  "version": null,
                  "file": "DLC.nsp",
                  "folder": "1DLC"
                }
              ],
              "profile": "0100F2C0115B6000",
              "name": "Tears of the Kingdom",
              "versions": [
                "1.4.0"
              ],
              "pack": "1.4.0",
              "android": {
                "id": "0100F2C0115B6000",
                "name": "Tears of the Kingdom",
                "android": true,
                "status": "playable",
                "tested": "1.4.3",
                "package": "org.nx2a.t0100f2c0115b6000"
              },
              "supported": true,
              "experimental": false,
              "reason": "",
              "compressed": []
            }
        """.trimIndent()

        val info = GameInfoJson.decode(output)!!
        assertEquals("0100F2C0115B6000", info.titleId)
        assertEquals("Tears of the Kingdom", info.name)
        assertEquals(GameProfileStatus.PLAYABLE, info.status)
        assertTrue(info.supported)
        assertFalse(info.experimental)
        assertEquals("TOTK.nsp", info.base)
        assertEquals(1, info.updates.size)
        assertEquals("TOTK update.nsp", info.updates[0].name)
        assertEquals("v1.4.0", info.updates[0].version)
        assertEquals("", info.updates[0].folder)
        assertEquals(1, info.dlc.size)
        assertEquals("DLC.nsp", info.dlc[0].name)
        assertEquals("", info.dlc[0].version)
        assertEquals("1DLC", info.dlc[0].folder)
        assertEquals("1.4.0", info.packVersion)
    }

    @Test
    fun `a game without a profile is an experimental one`() {
        val output = """{"title_id": "0100AAAA00000000", "base": "x.nsp", "updates": [], "dlc": [], """ +
            """"profile": null, "android": null, "versions": [], "pack": null, "supported": false, """ +
            """"experimental": true, "reason": "no game profile for 0100AAAA00000000", "compressed": []}"""

        val info = GameInfoJson.decode(output)!!
        assertEquals(GameProfileStatus.UNSUPPORTED, info.status)
        assertTrue(info.experimental)
        assertFalse(info.supported)
        assertEquals("", info.packVersion)
        assertEquals(0, info.updates.size)
        assertEquals(0, info.dlc.size)
    }

    @Test
    fun `a game with an iOS profile only is not ported to Android yet`() {
        val output = """{"title_id": "0100BBBB00000000", "profile": "0100BBBB00000000", "name": "Some Game", """ +
            """"android": null, "supported": false, "experimental": true, "pack": null}"""

        val info = GameInfoJson.decode(output)!!
        assertEquals(GameProfileStatus.IOS_ONLY, info.status)
        assertTrue(info.experimental)
        assertFalse(info.supported)
        assertEquals("Some Game", info.name)
    }

    @Test
    fun `a report that says it failed is no report`() {
        assertNull(GameInfoJson.decode("""{"error": "no game found (an NSP, XCI, NSZ or XCZ with a title ID)"}"""))
    }

    @Test
    fun `output without a report is no report`() {
        assertNull(GameInfoJson.decode(""))
        assertNull(GameInfoJson.decode("   "))
        assertNull(GameInfoJson.decode("nothing to read here"))
        assertNull(GameInfoJson.decode("[1, 2, 3]"))
    }

    @Test
    fun `a report without fields still reads`() {
        val info = GameInfoJson.decode("""{"title_id": "0100CCCC00000000"}""")!!
        assertEquals("0100CCCC00000000", info.titleId)
        assertEquals("", info.name)
        assertEquals(GameProfileStatus.UNSUPPORTED, info.status)
        assertEquals(0, info.updates.size)
        assertEquals(0, info.dlc.size)
        assertEquals("", info.packVersion)
        assertFalse(info.experimental)
    }

    @Test
    fun `the profile's status is mapped`() {
        assertEquals(GameProfileStatus.PLAYABLE, GameProfileStatus.of("playable"))
        assertEquals(GameProfileStatus.WIP, GameProfileStatus.of("wip"))
        assertEquals(GameProfileStatus.BOOTS, GameProfileStatus.of("boots"))
        assertEquals(GameProfileStatus.EXPERIMENTAL, GameProfileStatus.of("experimental"))
        // a status the app does not know yet is not worse than the best case
        assertEquals(GameProfileStatus.PLAYABLE, GameProfileStatus.of("something new"))
    }

    @Test
    fun `a game without a profile gets the experimental port`() {
        val noProfile = GameInfo(experimental = true)
        val ticked = BuildOptions.withExperimental(GameConfig(), noProfile)!!
        assertTrue(ticked.experimental)

        // already ticked: nothing to change
        assertNull(BuildOptions.withExperimental(ticked, noProfile))
        // a game with a profile: the option is the player's own
        assertNull(BuildOptions.withExperimental(GameConfig(), GameInfo(supported = true)))
        // no answer at all: nothing to change
        assertNull(BuildOptions.withExperimental(GameConfig(), null))
    }
}
