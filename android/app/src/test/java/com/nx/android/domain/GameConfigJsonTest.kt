package com.nx.android.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按游戏配置的存取：多出来的「额外地址空间」记得住，旧版本存下的配置读出来是 Auto */
class GameConfigJsonTest {

    @Test
    fun `the config survives the round trip`() {
        val c = GameConfig(touch = true, fps60 = false, experimental = true, aliasHeadroom = 16)
        val back = GameConfigJson.decode(GameConfigJson.encode(c))
        assertEquals(c, back)
        assertEquals(16, back.aliasHeadroom)
    }

    @Test
    fun `an old config without the alias reads as Auto`() {
        val old = JSONObject().put("touch", true).put("fps60", true).put("exp", false)
        val c = GameConfigJson.decode(old)
        assertEquals(ALIAS_AUTO, c.aliasHeadroom)
        assertTrue(c.touch)
        assertTrue(c.fps60)
        assertFalse(c.experimental)
    }

    @Test
    fun `Auto is what a fresh config carries`() {
        assertEquals(ALIAS_AUTO, GameConfig().aliasHeadroom)
        assertEquals(0, GameConfig().copy(aliasHeadroom = 0).aliasHeadroom)
    }

    /** 玩家的覆盖：自己的系统字体、多线程回放 */
    @Test
    fun `the player's own font and the replay survive the round trip`() {
        val c = GameConfig(sharedFont = "/sdcard/font.ttf", mtReplay = true)
        val back = GameConfigJson.decode(GameConfigJson.encode(c))
        assertEquals(c, back)
        assertEquals("/sdcard/font.ttf", back.sharedFont)
        assertTrue(back.mtReplay)
    }

    @Test
    fun `an old config without the new keys reads as unset`() {
        val old = JSONObject().put("touch", true).put("alias", 8)
        val c = GameConfigJson.decode(old)
        assertEquals("", c.sharedFont)
        assertFalse(c.mtReplay)
        assertEquals(8, c.aliasHeadroom)
    }

    @Test
    fun `a fresh config carries no override`() {
        val c = GameConfig()
        assertEquals("", c.sharedFont)
        assertFalse(c.mtReplay)
    }

    @Test
    fun `legacy filename config migrates to title ID while explicit title config wins`() {
        val legacy = GameConfig(touch = true, fps60 = true)
        val explicit = GameConfig(experimental = true)
        val configs = mutableMapOf(
            "game.nsp" to legacy,
            "0100000000010000" to explicit,
        )
        val games = listOf(
            GameEntry("0100000000010000", null, null, "/storage/first.nsp"),
            GameEntry("0100000000020000", null, null, "/storage/game.nsp"),
        )

        val changed = GameConfigMigration.migrateLegacy(configs, games)

        assertTrue(changed)
        assertEquals(explicit, configs["0100000000010000"])
        assertEquals(legacy, configs["0100000000020000"])
        assertFalse(configs.containsKey("game.nsp"))
    }
}
