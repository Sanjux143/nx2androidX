package com.nx.android.data

import com.nx.android.input.PadAssignment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 指派的那个文件：写进去再读出来是同一份；没有的文件就是没有指派；坏的文件也是没有指派。
 */
class PadPlayersStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file() = File(folder.root, "players/01004ab00a260000.json")

    @Test
    fun `nothing written means no assignment`() {
        assertNull(PadPlayersStore(file()).read())
    }

    @Test
    fun `what is written is read back`() {
        val store = PadPlayersStore(file())
        val assignment = PadAssignment(p1 = "device:aaaa", p2 = "device:bbbb")
        store.write(assignment)
        assertEquals(assignment, store.read())
    }

    @Test
    fun `an empty assignment is an assignment too`() {
        val store = PadPlayersStore(file())
        store.write(PadAssignment())
        assertEquals(PadAssignment(), store.read())
    }

    @Test
    fun `a broken file is no assignment`() {
        val store = PadPlayersStore(file())
        store.write(PadAssignment(p2 = "device:bbbb"))
        file().writeText("{ not json")
        assertNull(store.read())
    }

    @Test
    fun `a second write replaces the first`() {
        val store = PadPlayersStore(file())
        store.write(PadAssignment(p2 = "device:bbbb"))
        store.write(PadAssignment(p3 = "device:cccc"))
        assertEquals(PadAssignment(p3 = "device:cccc"), store.read())
    }
}
