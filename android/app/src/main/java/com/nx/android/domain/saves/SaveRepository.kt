package com.nx.android.domain.saves

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Where an exported package goes: the place the player picked in the file manager.
 *
 * The stream is opened by the repository and closed by it as well: the caller only says where.
 */
interface SaveTarget {
    /** the stream to write the package into, or null when the place cannot be opened */
    fun open(): OutputStream?
}

/**
 * Where an imported package comes from: the file the player picked in the file manager.
 *
 * The stream is opened by the repository and closed by it as well.
 */
interface SaveSource {
    /** the name the file manager shows: it tells whose save the package holds */
    val name: String
    /** the stream to read the package from, or null when the file cannot be opened */
    fun open(): InputStream?
}

/**
 * The game's save, out to a package and back in.
 *
 * The live save is the app's own file (the host mounts it at `<internal>/save/<titleId>`), so the save can
 * be worked on directly: no request file for the host, no game start.
 */
interface SaveRepository {
    /** the title ID a package's name carries, or null when it carries none */
    fun titleIdInPackage(name: String): String?

    /** the file name an export suggests: `<game>-<titleId>-<stamp>.zip` */
    fun suggestedPackageName(titleId: String, gameName: String): String

    /** writes the live save as a package into the target */
    suspend fun export(titleId: String, gameName: String, target: SaveTarget): Result<Unit>

    /** replaces the live save with the package from the source */
    suspend fun import(titleId: String, source: SaveSource): Result<Unit>
}

const val SAVE_PACKAGE_EXTENSION = "zip"

class SaveOperationException(val reason: Reason, detail: String? = null) :
    IOException(detail ?: reason.name) {
    enum class Reason { INVALID_TITLE_ID, TITLE_MISMATCH, NO_SAVES }
}
