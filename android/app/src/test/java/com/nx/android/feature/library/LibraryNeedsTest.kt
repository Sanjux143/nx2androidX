package com.nx.android.feature.library

import com.nx.android.data.library.GameProfile
import com.nx.android.domain.Ported
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** 库页上的那几个标记：这款游戏需要什么 */
class LibraryNeedsTest {

    private fun profile(
        status: String = "playable",
        graphics: String = "",
        lan: Boolean = false,
        requires: List<String> = emptyList(),
    ) = GameProfile(status = status, graphics = graphics, lan = lan, requires = requires)

    private fun ported(status: String = "playable") = Ported(
        pack = File("/tmp/pack"), host = File("/tmp/pack/host/libdmplay.so"),
        icon = null, titleId = "0100000000000000", status = status,
    )

    @Test
    fun `a game with nothing known shows nothing`() {
        assertEquals(emptyList<GameNeed>(), needsOf(null, null))
        assertEquals(emptyList<GameNeed>(), needsOf(profile(), null))
    }

    @Test
    fun `an OpenGL ES game says so`() {
        assertEquals(listOf(GameNeed.GL), needsOf(profile(graphics = "gl"), null))
        assertEquals(emptyList<GameNeed>(), needsOf(profile(graphics = "vulkan"), null))
    }

    @Test
    fun `a game that plays over the local network says so`() {
        assertEquals(listOf(GameNeed.LAN), needsOf(profile(lan = true), null))
    }

    @Test
    fun `the profile's state shows while the game is not ported`() {
        assertEquals(listOf(GameNeed.WIP), needsOf(profile(status = "wip"), null))
        assertEquals(listOf(GameNeed.BOOTS), needsOf(profile(status = "boots"), null))
        assertEquals(listOf(GameNeed.EXPERIMENTAL), needsOf(profile(status = "experimental"), null))
    }

    @Test
    fun `a ported game already says its state in the pack`() {
        assertEquals(emptyList<GameNeed>(), needsOf(profile(status = "experimental"), ported(status = "experimental")))
    }

    @Test
    fun `a pack that says nothing lets the profile answer`() {
        val p = ported().copy(graphics = "", lan = null)
        assertEquals(listOf(GameNeed.GL, GameNeed.LAN), needsOf(profile(graphics = "gl", lan = true), p))
    }

    @Test
    fun `the pack wins when it says`() {
        val p = ported().copy(graphics = "gl", lan = true)
        assertEquals(listOf(GameNeed.GL, GameNeed.LAN), needsOf(profile(graphics = "nvn", lan = false), p))
    }
}
