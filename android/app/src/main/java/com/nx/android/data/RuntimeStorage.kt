package com.nx.android.data

import com.nx.android.BuildConfig
import com.nx.android.core.storage.AppPaths
import com.nx.android.domain.PlayerApiRevision
import com.nx.android.domain.Ported
import com.nx.android.domain.RuntimeBuildMarker
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 运行库的统一目录（filesDir/runtimes/<title ID 小写>）：每游戏一份（构建选项编进宿主，不同选项编出来的
 * 宿主不是同一个东西）。目录里的 build.json 是构建标记——启动前的指纹比对只读它；移植、重建、旧包晋升
 * 三个写点都要更新它（标记最后写：没有标记的运行库会被判定为要重建，不会被半知半解地加载）。
 *
 * 共享存储 noexec：.so 必须落在私有目录才能被 dlopen。启动零拷贝——游戏直接加载目录里这一份。
 */
@Singleton
class RuntimeStorage @Inject constructor(private val paths: AppPaths) {

    companion object {
        const val MARKER_FILE = "build.json"
        private const val HOST_FILE = "libdmplay.so"

        /** the managed hooks, beside the host (the loader's own library loads them) */
        val HOOKS = setOf("libhook_impl.so", "libmain_hook.so", "libfile_redirect_hook.so", "libgsl_alloc_hook.so")

        /** what the app expects a runtime to have been built with right now */
        fun expected(): RuntimeBuildMarker = RuntimeBuildMarker(
            playerApiRevision = PlayerApiRevision.CURRENT,
            profileH = "",           // compared against the pack's own manifest, not a constant
            noLsfg = !BuildConfig.NX_LSFG,
        )
    }

    /** the folder that holds this game's runtime (one per title ID; the ID is normalized to lowercase) */
    fun dir(titleId: String): File = File(paths.files, "runtimes/${titleId.lowercase()}")

    /** the build marker a folder carries, or null when there is none (a half-made or foreign runtime) */
    fun readMarker(dir: File): RuntimeBuildMarker? = try {
        val o = JSONObject(File(dir, MARKER_FILE).readText())
        RuntimeBuildMarker(
            playerApiRevision = o.optInt("player_api_revision", 0),
            profileH = o.optString("profile_h"),
            noLsfg = if (o.has("no_lsfg")) o.optBoolean("no_lsfg") else null,
        )
    } catch (_: Exception) {
        null
    }

    fun readMarker(titleId: String): RuntimeBuildMarker? = readMarker(dir(titleId))

    /** the host this folder's runtime loads (the file must be there: [complete] says whether it is) */
    fun hostOf(dir: File): File = File(dir, HOST_FILE)

    /** the folder holds a whole runtime: the host and the loader's hooks, nothing half in place */
    fun complete(dir: File): Boolean =
        dir.isDirectory && hostOf(dir).isFile && HOOKS.all { File(dir, it).isFile }

    /**
     * The folder's runtime matches what this pack needs: the marker is there, built for the current
     * interface, its lsfg switch the app's own (when the marker knows it), and its profile the one this
     * pack was built with (when both sides say which that was).
     */
    fun matches(ported: Ported, marker: RuntimeBuildMarker?): Boolean {
        if (marker == null) return false
        val expected = expected()
        if (marker.playerApiRevision != expected.playerApiRevision) return false
        if (marker.noLsfg != null && marker.noLsfg != expected.noLsfg) return false
        val packProfile = ported.profileH
        if (packProfile.isNotEmpty() && marker.profileH.isNotEmpty() && packProfile != marker.profileH) return false
        return true
    }

    /** writes the marker into place (atomic; the marker goes in last, after the runtime's files) */
    fun writeMarker(dir: File, marker: RuntimeBuildMarker) {
        dir.mkdirs()
        val pending = File(dir, "$MARKER_FILE.part")
        pending.writeText(JSONObject().let { o ->
            o.put("player_api_revision", marker.playerApiRevision)
            o.put("profile_h", marker.profileH)
            marker.noLsfg?.let { o.put("no_lsfg", it) }
            o.toString()
        })
        if (!pending.renameTo(File(dir, MARKER_FILE))) {
            pending.copyTo(File(dir, MARKER_FILE), overwrite = true)
            pending.delete()
        }
    }
}
