package com.nx.android.data

import java.io.File

/**
 * The game's runtime log: the host writes its whole stderr into `log.txt` in the game's data pack
 * (front_player.c's host_start_common), and the run before it into `log.prev.txt` — the host renames the log
 * at every start, so a crash's backtrace is in the one before the last. The app shares those as a bundle: the
 * device's facts, the game's facts and the logs.
 */
object GameLog {

    /** the host's log in the data pack: the last run */
    const val FILE = "log.txt"

    /** the run before that (the host renames the log at every start) */
    const val PREV_FILE = "log.prev.txt"

    /** the log of the last run in that pack, or null when the game has never run */
    fun inPack(pack: File): File? = File(pack, FILE).takeIf { it.isFile }

    /** the log of the run before the last one: a crash's backtrace is in here */
    fun previousInPack(pack: File): File? = File(pack, PREV_FILE).takeIf { it.isFile }

    /** the name the shared bundle carries: the game's own name, safe in a file name */
    fun shareName(game: String): String = "${SaveBackup.sanitize(game).ifEmpty { "game" }}-log.zip"

    /** The game's own facts for the bundle (the game, its identity, the version it was built at) */
    fun facts(game: String, titleId: String, version: String, status: String, playerApiRevision: Int): String =
        "game: $game\ntitle id: $titleId\nversion: $version\nprofile status: $status\n" +
            "player api revision: $playerApiRevision\n"

    /** The host's logs in that pack: the last run and the one before it */
    fun logs(pack: File): List<Pair<String, File>> = listOfNotNull(
        inPack(pack)?.let { FILE to it },
        previousInPack(pack)?.let { PREV_FILE to it },
    )

    /**
     * The bundle in dest: the device, the game and the host's logs of the last two runs.
     * Null when the game has never run: there is no log to share.
     *
     * @param device the device's and the app's own facts as text
     * @param game the game's own name (the bundle's file name carries it)
     * @param facts the game's own facts as text
     */
    fun bundle(device: String, game: String, facts: String, pack: File, dest: File): File? =
        LogBundle.gameBundle(device, facts, logs(pack), dest, shareName(game))
}
