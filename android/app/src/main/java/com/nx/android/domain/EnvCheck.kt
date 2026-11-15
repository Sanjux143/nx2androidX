package com.nx.android.domain

import org.json.JSONArray

/**
 * The environment's checks: the two that are cheap and the rows the doctor reports.
 *
 * The chip's verdict is the cheap ones only - the environment is prepared and the keys file is there - because
 * they are read from the filesystem and can be looked at whenever the screen comes back. The doctor's rows are
 * the expensive ones: they run a python inside the rootfs, so they are asked for on purpose.
 *
 * The third thing the chip looks at is Android's limit on the app's child processes: its state is already held
 * (it is read on a thread of its own), so colouring the chip with it costs nothing.
 */
object EnvCheck {

    /** The doctor's row about the keys: the checklist has its own, cheaper row for them */
    const val KEYS_ROW = "prod.keys"

    /** The doctor's row about adb: the phone cannot run it (Google ships platform-tools for Linux as x86-64 only) */
    const val ADB_ROW = "adb"

    /** What a check says */
    enum class Level { OK, HINT, BAD }

    /** The chip's state */
    enum class Chip { CHECKING, READY, HINT, NOT_READY }

    /** One line of the checklist */
    data class Row(val name: String, val level: Level, val detail: String, val fix: String = "")

    /**
     * The chip's verdict: the environment and the keys are the two things it always looks at, and Android's
     * limit on the app's child processes is the third - a hint, not a failure, so it only colours a chip that
     * would otherwise be green.
     *
     * The firmware is a fourth, the same kind of hint: it is optional (only a game that reads the console's
     * system data needs it), so a missing one is amber, not red - the app works without it.
     *
     * @param phantom the limit's state, null when it has not been read yet: the chip must not flash amber
     *   while the answer is still on its way
     * @param firmwareImported whether the machine's firmware has been imported
     */
    fun chip(envReady: Boolean, keysOk: Boolean, phantom: PhantomCheck.Status? = null, firmwareImported: Boolean = true): Chip = when {
        !envReady || !keysOk -> Chip.NOT_READY
        !firmwareImported -> Chip.HINT
        phantom == PhantomCheck.Status.ON || phantom == PhantomCheck.Status.UNKNOWN -> Chip.HINT
        else -> Chip.READY
    }

    /**
     * The doctor's rows, in the order it printed them.
     *
     * A row that is not required and did not pass is a hint, not a failure: the doctor itself only fails for
     * the required ones. Two rows are left out: the keys (the checklist has its own, cheaper row for them) and
     * adb (the phone cannot run it, so the row tells the player nothing they can act on).
     */
    fun rows(json: JSONArray): List<Row> {
        val out = mutableListOf<Row>()
        for (i in 0 until json.length()) {
            val r = json.optJSONObject(i) ?: continue
            val name = r.optString("name")
            if (name.isEmpty() || name == KEYS_ROW || name == ADB_ROW) continue
            val ok = r.optBoolean("ok")
            val required = r.optBoolean("required", true)
            out += Row(
                name,
                when {
                    ok -> Level.OK
                    required -> Level.BAD
                    else -> Level.HINT
                },
                r.optString("detail"),
                // the tool's fix belongs to a row that did not pass: the CLI prints it only then, and a row
                // that is fine must not say "pip install ..." (the phone has no pip: the modules are in the payload)
                if (ok) "" else r.optString("fix"),
            )
        }
        return out
    }
}
