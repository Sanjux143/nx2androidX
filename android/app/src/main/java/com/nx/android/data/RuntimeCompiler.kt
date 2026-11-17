package com.nx.android.data

import com.nx.android.BuildConfig
import com.nx.android.domain.PortEvents
import com.nx.android.domain.environment.GuestProcessLauncher
import java.io.File
import javax.inject.Inject

/** The outcome of a runtime rebuild: whether it worked, and the reason it did not. */
data class RuntimeUpdateResult(val success: Boolean, val reason: String = "")

/**
 * Executes only rebuild-runtime; a successful process must also report the requested pack. The rebuild
 * always carries --allow-profile-drift: a pack whose recorded profile no longer matches any candidate is
 * rebuilt with the profile the game has now, without asking — the loading page carries the hint that a
 * misbehaving game is ported again.
 */
class RuntimeCompiler @Inject constructor(private val guestLauncher: GuestProcessLauncher) {
    fun rebuild(pack: String, noLsfg: Boolean = !BuildConfig.NX_LSFG,
                log: (String) -> Unit = AppLog::line): RuntimeUpdateResult {
        var committed = false
        var reason = ""
        return try {
            val process = guestLauncher.guest(args(pack, noLsfg))
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val event = PortEvents.parse(line)
                    if (event?.kind == "result") {
                        committed = File(event.data).canonicalFile == File(pack).canonicalFile
                    }
                    val message = event?.message?.takeIf { it.isNotBlank() } ?: line
                    log(message)
                    // the porter's own failure line is what the player needs to see: it says why the rebuild stopped
                    if (message.startsWith("[RED] ")) reason = message.removePrefix("[RED] ")
                }
            }
            val code = process.waitFor()
            if (code != 0) log("[RED] Runtime compiler exited with code $code")
            val success = code == 0 && committed
            val why = when {
                success -> ""
                reason.isNotEmpty() -> reason
                code != 0 -> "Runtime compiler exited with code $code"
                else -> "The runtime rebuild did not report this pack"
            }
            RuntimeUpdateResult(success, why)
        } catch (error: Exception) {
            log("[RED] Runtime rebuild failed: ${error.message}")
            RuntimeUpdateResult(false, error.message.orEmpty())
        }
    }

    companion object {
        fun args(pack: String, noLsfg: Boolean): List<String> {
            val args = mutableListOf("-m", "porter", "rebuild-runtime", pack, "--progress", "json")
            if (noLsfg) args.add("--no-lsfg")
            // the profile the game has now is always the one a rebuild uses (no confirmation step)
            args.add("--allow-profile-drift")
            return args
        }
    }
}
