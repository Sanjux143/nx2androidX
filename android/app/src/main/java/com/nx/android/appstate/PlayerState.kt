package com.nx.android.appstate

import android.content.Context
import com.nx.android.data.Player
import com.nx.android.data.PrefsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The player's door: the one place that answers "is a game running, and which one" and the one place
 * that records which game was launched. There is no state here and nothing polls: the `:play` process's
 * presence is asked right when a decision needs it (the launch gate, the cold start, the crash check),
 * and the identity (title id and name) lives in the preferences — the memory of the last launch. When
 * the process is gone the identity is simply ignored, so there is nothing to clear and no latency.
 *
 * The premise underneath: the library page is only ever visible while no game runs (the game page is a
 * singleTask page that covers it, and ending the game returns to it) — so a running game is only ever
 * asked about at the launch gate and on a cold start.
 */
@Singleton
class PlayerState private constructor(private val identity: IdentityStore, marker: Unit) {

    @Inject
    constructor(prefs: PrefsStore) : this(PrefsIdentityStore(prefs), Unit)

    constructor(identity: IdentityStore) : this(identity, Unit)

    /** the ":play" process's presence, asked right now (this is the app's one door to that question) */
    fun processAlive(context: Context): Boolean = Player.playProcessAlive(context)

    /** the launch gate is letting a game through: record whose it is (a cold start reads it back) */
    fun markLaunched(titleId: String, name: String) = identity.save(titleId, name)

    fun currentTitleId(): String = identity.loadTitleId()

    fun currentName(): String = identity.loadName()
}

/** The identity's store: what a cold start reads the last launched game back from. */
interface IdentityStore {
    fun save(titleId: String, name: String)
    fun loadTitleId(): String
    fun loadName(): String
    fun clear()
}

/** The real store: the app's preferences. */
class PrefsIdentityStore(private val prefs: PrefsStore) : IdentityStore {
    override fun save(titleId: String, name: String) {
        prefs.put(PrefsStore.PLAYER_TITLE_ID, titleId)
        prefs.put(PrefsStore.PLAYER_NAME, name)
    }

    override fun loadTitleId(): String = prefs.str(PrefsStore.PLAYER_TITLE_ID)

    override fun loadName(): String = prefs.str(PrefsStore.PLAYER_NAME)

    override fun clear() {
        prefs.put(PrefsStore.PLAYER_TITLE_ID, null)
        prefs.put(PrefsStore.PLAYER_NAME, null)
    }
}

/** The tests' store: in memory. */
class MemoryIdentityStore : IdentityStore {
    private var titleId = ""
    private var name = ""
    override fun save(titleId: String, name: String) { this.titleId = titleId; this.name = name }
    override fun loadTitleId(): String = titleId
    override fun loadName(): String = name
    override fun clear() { titleId = ""; name = "" }
}
