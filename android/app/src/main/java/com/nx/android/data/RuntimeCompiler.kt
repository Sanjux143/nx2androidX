package com.nx.android.data

import com.nx.android.BuildConfig
import com.nx.android.domain.PortEvents
import com.nx.android.domain.environment.GuestProcessLauncher
import java.io.File
import javax.inject.Inject

/** The outcome of a runtime update: whether it worked, and the reason it did not. */
data class RuntimeUpdateResult(val success: Boolean, val reason: String = "", val profileDrift: Boolean = false)

/** Executes only rebuild-runtime; a successful process must also report the requested pack. */
class RuntimeCompiler @Inject constructor(private val guestLauncher: GuestProcessLauncher) {
    fun rebuild(pack: String, noLsfg: Boolean = !BuildConfig.NX_LSFG, allowProfileDrift: Boolean = false,
                log: (String) -> Unit = AppLog::line): RuntimeUpdateResult {
        var committed = false
        var reason = ""
        var drift = false
        return try {
            val process = guestLauncher.guest(args(pack, noLsfg, allowProfileDrift))
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val event = PortEvents.parse(line)
                    if (event?.kind == "result") {
                        committed = File(event.data).canonicalFile == File(pack).canonicalFile
                    }
                    // 档案对不上：玩家还可以让 App 用当前档案再来一次
                    if (event?.kind == "profile-drift") drift = true
                    val message = event?.message?.takeIf { it.isNotBlank() } ?: line
                    log(message)
                    // the porter's own failure line is what the player needs to see: it says why the update stopped
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
                else -> "The runtime update did not report this pack"
            }
            RuntimeUpdateResult(success, why, drift)
        } catch (error: Exception) {
            log("[RED] Runtime update failed: ${error.message}")
            RuntimeUpdateResult(false, error.message.orEmpty())
        }
    }

    companion object {
        fun args(pack: String, noLsfg: Boolean, allowProfileDrift: Boolean = false): List<String> {
            val args = mutableListOf("-m", "porter", "rebuild-runtime", pack, "--progress", "json")
            if (noLsfg) args.add("--no-lsfg")
            // the player asked to rebuild with the profile the game has now
            if (allowProfileDrift) args.add("--allow-profile-drift")
            return args
        }
    }
}
