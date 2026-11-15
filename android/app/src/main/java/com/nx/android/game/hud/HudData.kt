package com.nx.android.game.hud

import java.util.Locale

/**
 * The performance HUD's logic, free of Android: the fields, the readings, the frame rate's arithmetic and the
 * rendering into lines. The samplers (HudSampler) and the view (ui/game/PerfHud.kt) sit around it.
 */

/** The fields the HUD can show, in the order they are drawn. */
enum class HudField(val key: String) {
    FPS("FPS"), FRAME_TIME("FT"), CPU("CPU"), GPU("GPU"), APP_RAM("RAM"), SYS_RAM("MEM"), TEMP("TEMP"), POWER("POWER");

    companion object {
        /** What the HUD shows when the player has not chosen otherwise */
        val DEFAULT = setOf(FPS, TEMP)

        fun of(keys: Collection<String>): Set<HudField> =
            keys.mapNotNull { k -> entries.firstOrNull { it.key == k } }.toSet()

        fun keys(fields: Collection<HudField>): String = fields.joinToString(",") { it.key }
    }
}

/** The color a line is drawn with: the pill's palette (the app's own colors are mapped onto it in the view) */
enum class HudRole { PLAIN, ACCENT, GOOD, WARN, BAD }

/** One line of the HUD: the reading and the color role it wears */
data class HudLine(val text: String, val role: HudRole)

/**
 * One tick's readings. A null field is one the device does not offer (or one that was not sampled): the HUD
 * draws a placeholder for it instead of a made-up number.
 */
data class HudSample(
    val gameFps: Double? = null,
    val presentedFps: Double? = null,
    val frameMs: Double? = null,
    val fgState: Int? = null,
    val cpuPercent: Float? = null,
    val gpuPercent: Float? = null,
    val appRamMb: Long? = null,
    val sysRamUsedMb: Long? = null,
    val sysRamTotalMb: Long? = null,
    val tempC: Float? = null,
    val powerW: Float? = null,
    val capacity: Int? = null,
    val charging: Boolean = false,
)

/** The host's counters, one sample: the game's frames, the generation's frames, the frame intervals (sum and count), the refresh */
data class HostSample(
    val gameFrames: Long,
    val genFrames: Long,
    val spanNs: Long,
    val spanCount: Long,
    val refreshNs: Long,
)

/** What two host samples, an interval apart, say about the frame rate */
data class FpsStats(val gameFps: Double?, val presentedFps: Double?, val frameMs: Double?)

object HudData {

    /** The battery's warning and bad marks, in Celsius (the battery runs far cooler than the SoC) */
    const val TEMP_WARN_C = 42f
    const val TEMP_BAD_C = 47f

    /** What a field reads when the device does not offer it */
    const val PLACEHOLDER = "—"

    /** The host's frame generation states (dm_framegen.h): off, generating, on but not generating, unavailable */
    const val FG_OFF = 0
    const val FG_GENERATING = 1
    const val FG_ON = 2
    const val FG_UNAVAILABLE = -1

    /** The host's status line ("<state> <reason>"): its state, null when there is none */
    fun fgState(status: String?): Int? = status?.trim()?.substringBefore(' ')?.toIntOrNull()

    /**
     * The frame rate between two host samples: the game's own frames a second, what reaches the screen with the
     * frame generation on (both together), and the mean frame time (the intervals between the game's frames).
     * The first sample has nothing to compare with: everything is null then.
     */
    fun stats(prev: HostSample?, cur: HostSample, elapsedMs: Long): FpsStats {
        if (prev == null || elapsedMs <= 0) return FpsStats(null, null, null)
        val game = cur.gameFrames - prev.gameFrames
        val gen = cur.genFrames - prev.genFrames
        val spans = cur.spanCount - prev.spanCount
        val spanNs = cur.spanNs - prev.spanNs
        val gameFps = if (game > 0) game * 1000.0 / elapsedMs else null
        val shown = game + gen
        val presentedFps = if (shown > 0) shown * 1000.0 / elapsedMs else null
        val frameMs = if (spans > 0 && spanNs > 0) spanNs / 1e6 / spans else null
        return FpsStats(gameFps, presentedFps, frameMs)
    }

    /**
     * One line per selected field, in the fixed order. The frame rate is the star and wears the accent; the
     * temperature grades good, warn and bad. With the frame generation on, the frame rate names the engine and
     * shows what reaches the screen — one number, the presented rate (the game's own frames and the generated
     * ones). The game's own rate is not shown beside it: the player would have to work out which of the two is
     * the one they feel, and the frame time (the game's own frame interval) would not match it.
     *
     * The label follows the host's state, not whether frames happened to be generated in the last second: the
     * generation holds itself back now and then (the game's rate is not steady enough, or the panel has no
     * refresh left), and a label that blinked with it told the player nothing.
     */
    fun render(
        s: HudSample,
        fields: Set<HudField>,
        fahrenheit: Boolean = false,
        engineLabel: String? = null,
        chargingText: String = "charging",
    ): List<HudLine> {
        val fgOn = s.fgState == FG_GENERATING || s.fgState == FG_ON
        val label = when {
            fgOn -> engineLabel ?: "LSFG"
            s.fgState != null && s.fgState < 0 -> "no LSFG"
            else -> null
        }
        return HudField.entries.filter { it in fields }.map { field ->
            when (field) {
                HudField.FPS -> {
                    val text = when {
                        s.gameFps == null -> PLACEHOLDER
                        else -> {
                            val fps = if (fgOn && s.presentedFps != null) s.presentedFps else s.gameFps
                            (if (label != null) "$label " else "") + "${fps.toInt()} fps"
                        }
                    }
                    HudLine(text, HudRole.ACCENT)
                }
                HudField.FRAME_TIME -> if (fgOn) null else HudLine(s.frameMs?.let { num("%.1f ms", it) } ?: PLACEHOLDER, HudRole.PLAIN)
                HudField.CPU -> HudLine(s.cpuPercent?.let { num("CPU %.0f%%", it) } ?: "CPU $PLACEHOLDER", HudRole.PLAIN)
                HudField.GPU -> HudLine(s.gpuPercent?.let { num("GPU %.0f%%", it) } ?: "GPU $PLACEHOLDER", HudRole.PLAIN)
                HudField.APP_RAM -> HudLine(s.appRamMb?.let { num("RAM %d MB", it) } ?: "RAM $PLACEHOLDER", HudRole.PLAIN)
                HudField.SYS_RAM -> HudLine(
                    if (s.sysRamUsedMb != null && s.sysRamTotalMb != null)
                        num("MEM %.1f/%.1f GB", s.sysRamUsedMb / 1024.0, s.sysRamTotalMb / 1024.0)
                    else "MEM $PLACEHOLDER",
                    HudRole.PLAIN,
                )
                HudField.TEMP -> {
                    val c = s.tempC
                    val text = c?.let { if (fahrenheit) num("%.1f°F", it * 9.0 / 5.0 + 32.0) else num("%.1f°C", it) } ?: PLACEHOLDER
                    HudLine(text, tempRole(c))
                }
                // The battery's current is the charge current while it charges: a marker, not watts. The percentage rides along.
                HudField.POWER -> HudLine(
                    when {
                        s.charging -> listOfNotNull(chargingText, s.capacity?.let { "$it%" }).joinToString(" ")
                        s.powerW != null -> listOfNotNull(num("%.1f W", s.powerW), s.capacity?.let { "$it%" }).joinToString(" ")
                        else -> s.capacity?.let { "$it%" } ?: PLACEHOLDER
                    },
                    if (s.charging) HudRole.ACCENT else HudRole.PLAIN,
                )
            }
        }.filterNotNull()
    }

    /** A number in the app's own format: the decimal point is a point, whatever the phone's language is */
    private fun num(format: String, vararg values: Any): String = String.format(Locale.ROOT, format, *values)

    /** The temperature's color: good below the warning mark, warning to the bad mark, bad above it (in Celsius) */
    fun tempRole(c: Float?): HudRole = when {
        c == null -> HudRole.PLAIN
        c >= TEMP_BAD_C -> HudRole.BAD
        c >= TEMP_WARN_C -> HudRole.WARN
        else -> HudRole.GOOD
    }
}
