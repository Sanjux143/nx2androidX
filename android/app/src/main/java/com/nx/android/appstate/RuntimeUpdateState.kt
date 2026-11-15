package com.nx.android.appstate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nx.android.data.PrefsStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class RuntimeUpdatePhase { IDLE, RUNNING, SUCCEEDED, FAILED }

/** One modal operation, independent of port progress, history and source-file associations. */
@Singleton
class RuntimeUpdateState private constructor(
    private val savePending: (String?) -> Unit,
    pending: String?,
) {
    @Inject constructor(prefs: PrefsStore) : this(
        { prefs.put(PrefsStore.RUNTIME_PENDING_PACK, it) },
        prefs.str(PrefsStore.RUNTIME_PENDING_PACK).takeIf { it.isNotBlank() },
    )

    constructor(pending: String? = null) : this({}, pending)

    var pack by mutableStateOf(pending.orEmpty()); private set
    var name by mutableStateOf(pending?.let { File(it).name }.orEmpty()); private set
    var interrupted by mutableStateOf(pending != null); private set
    var reason by mutableStateOf(""); private set
    var phase by mutableStateOf(if (pending == null) RuntimeUpdatePhase.IDLE else RuntimeUpdatePhase.FAILED); private set
    val running: Boolean get() = phase == RuntimeUpdatePhase.RUNNING

    /** 这次失败是「档案对不上」：玩家可以让它用当前档案再来一次 */
    var profileDrift by mutableStateOf(false); private set

    /** 下一次更新用当前档案（玩家点了「用当前档案继续」；成功之后清掉） */
    var allowDrift by mutableStateOf(false); private set

    fun begin(path: String, label: String): Boolean {
        if (running || path.isBlank()) return false
        pack = path
        name = label
        interrupted = false
        reason = ""
        profileDrift = false
        savePending(path)
        phase = RuntimeUpdatePhase.RUNNING
        return true
    }

    fun finish(success: Boolean, why: String = "", profileDrift: Boolean = false) {
        if (!running) return
        savePending(null)
        reason = if (success) "" else why
        this.profileDrift = profileDrift
        // 这一次用的档案已经记进清单：下次按新指纹核对，不再问
        allowDrift = false
        phase = if (success) RuntimeUpdatePhase.SUCCEEDED else RuntimeUpdatePhase.FAILED
    }

    /** 玩家让 App 用当前档案再来一次（这份档案就是完整重新移植会用的那份） */
    fun useCurrentProfile() {
        allowDrift = true
    }

    /** 这一轮的更新用当前档案吗（玩家点了「用当前档案继续」才有）：只对这一次有效 */
    fun takeDrift(): Boolean {
        val drift = allowDrift
        allowDrift = false
        return drift
    }

    fun dismiss() {
        if (running) return
        savePending(null)
        phase = RuntimeUpdatePhase.IDLE
        pack = ""
        name = ""
        interrupted = false
        reason = ""
        profileDrift = false
        allowDrift = false
    }
}
