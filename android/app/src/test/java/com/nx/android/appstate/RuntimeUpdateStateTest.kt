package com.nx.android.appstate

import org.junit.Assert.*
import org.junit.Test

class RuntimeUpdateStateTest {
    @Test fun `duplicate taps and dismissal cannot release an active update`() {
        val state = RuntimeUpdateState()
        assertTrue(state.begin("/pack/Game-data", "Game"))
        assertFalse(state.begin("/pack/Other-data", "Other"))
        state.dismiss()
        assertTrue(state.running)
        assertEquals("/pack/Game-data", state.pack)
        state.finish(true)
        assertEquals(RuntimeUpdatePhase.SUCCEEDED, state.phase)
        state.dismiss()
        assertEquals(RuntimeUpdatePhase.IDLE, state.phase)
    }

    @Test fun `failure keeps the target for retry without a build task`() {
        val state = RuntimeUpdateState()
        state.begin("/pack/Game-data", "Game")
        state.finish(false)
        assertFalse(state.running)
        assertEquals(RuntimeUpdatePhase.FAILED, state.phase)
        assertTrue(state.begin(state.pack, state.name))
        assertTrue(state.running)
        assertFalse(state.interrupted)
    }

    @Test fun `the failure reason reaches the dialog and a retry clears it`() {
        val state = RuntimeUpdateState()
        state.begin("/pack/Game-data", "Game")
        state.finish(false, "The game profile changed since this pack was built.")
        assertEquals("The game profile changed since this pack was built.", state.reason)
        state.begin(state.pack, state.name)
        assertEquals("", state.reason)
        state.dismiss()
        assertEquals("", state.reason)
    }

    @Test fun `a changed profile is remembered and the player can continue with the current profile`() {
        val state = RuntimeUpdateState()
        state.begin("/pack/Game-data", "Game")
        state.finish(false, "The game profile changed since this pack was built.", profileDrift = true)
        assertTrue(state.profileDrift)     // the dialog offers the current profile
        assertFalse(state.allowDrift)
        state.useCurrentProfile()
        assertTrue(state.allowDrift)
        state.begin(state.pack, state.name)   // the update the player asked for
        assertTrue(state.takeDrift())         // that one carries it
        assertFalse(state.takeDrift())        // and only that one
        state.finish(true)
        assertFalse(state.allowDrift)         // the new fingerprint is in the manifest: no second question
    }

    @Test fun `a plain retry does not take the current profile`() {
        val state = RuntimeUpdateState()
        state.begin("/pack/Game-data", "Game")
        state.finish(false, "The game profile changed since this pack was built.", profileDrift = true)
        state.begin(state.pack, state.name)   // the retry, without the player's choice
        assertFalse(state.takeDrift())
        state.finish(false, "The game profile changed since this pack was built.", profileDrift = true)
        assertTrue(state.profileDrift)        // the dialog offers it again
    }

    @Test fun `another failure offers no current profile`() {
        val state = RuntimeUpdateState()
        state.begin("/pack/Game-data", "Game")
        state.finish(false, "The Android compiler is unavailable.")
        assertFalse(state.profileDrift)
        assertFalse(state.allowDrift)
    }

    @Test fun `a surviving pending marker restores a dismissible interruption prompt`() {
        val state = RuntimeUpdateState("/pack/Game-data")
        assertEquals(RuntimeUpdatePhase.FAILED, state.phase)
        assertTrue(state.interrupted)
        assertFalse(state.running)
        state.dismiss()
        assertEquals(RuntimeUpdatePhase.IDLE, state.phase)
        assertEquals("", state.pack)
    }
}
