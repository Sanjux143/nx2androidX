package com.nx.android.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import com.nx.android.core.storage.AppPaths
import com.nx.android.domain.environment.EnvironmentSetup
import com.nx.android.domain.environment.GuestProcessLauncher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The environment the porter runs in.
 *
 * The app is a shell: it unpacks the rootfs (from the nixport payload), the build tools, the .NET runtime and the tool's
 * own tree into its files folder, and runs the porter inside it. The guest programs are Linux binaries: Android has
 * no /lib and the guest is not a chroot, so they are started through the rootfs's dynamic loader, and the programs
 * the porter starts itself (the compiler, the .NET runtime) get their interpreter path rewritten to the app's own rootfs.
 */
@Singleton
class Env @Inject constructor(@ApplicationContext private val ctx: Context, private val paths: AppPaths) : EnvironmentSetup, GuestProcessLauncher {

    companion object {
        const val TAG = "Nx2aEnv"

        /**
         * The generation of the build's stamp: it changes when the rule "the tree goes and comes again"
         * changes, so the phones that were updated with the older rule (which only wrote over the old
         * files and never removed them) do the wipe once, when they install an app with this rule.
         */
        private const val STAMP_GEN = "wipe1:"
    }

    val files = paths.files
    val rootfs = paths.rootfs
    val tool = paths.tool
    val home = paths.home
    val nxHome = paths.nxHome
    val tmp = paths.tmp

    /** 移植产出的数据包：移植器写在这里（NX2A_WORK 的旁边），游戏库也只在这里找包 */
    val ports = paths.ports

    /** 移植的工作区（NX2A_WORK）：解包、重写、着色器都在这里，一个游戏一个子目录 */
    val work = paths.work

    val loader = File(rootfs, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
    val python = File(rootfs, "usr/bin/python3.13")
    val interpPath = "${rootfs.absolutePath}/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"

    /** The environment is ready when the rootfs, the tool and the compiler are there */
    override val ready: Boolean
        get() = python.exists() && File(tool, "porter").isDirectory && File(tool, "toolchain/clang/bin/clang").exists()

    /** The .NET runtime (the extractor and the shader translator are C# and run on it): where the porter looks for it */
    val dotnet: File get() = File(nxHome, "toolchains/dotnet/dotnet")

    /** The environment every guest program gets */
    override fun guestEnv(): MutableMap<String, String> {
        home.mkdirs(); tmp.mkdirs(); nxHome.mkdirs()
        return mutableMapOf(
            "PATH" to "${tool}/toolchain/clang/bin:${tool}/toolchain/bin:${rootfs}/usr/bin",
            "HOME" to home.absolutePath,
            "TMPDIR" to tmp.absolutePath,
            "PYTHONHOME" to "${rootfs}/usr",
            "PYTHONPATH" to "${tool}/pylibs",
            "LD_LIBRARY_PATH" to "${rootfs}/usr/lib/aarch64-linux-gnu:${rootfs}/usr/lib",
            "NX2A_HOME" to nxHome.absolutePath,
            "NX2A_NDK" to "${tool}/toolchain/ndk",
            "NX2A_REPO" to tool.absolutePath,
            // nx_extract is built on the computer and shipped: the phone has no SDK to restore packages with
            // (no network), and its globalization mode makes the SDK itself crash
            "NX2A_NX_EXTRACT" to "${tool}/tools/nx_extract",
            // the build folder: Android does not let the app run a program on the shared storage, so the
            // port's own tools and the unpacked game live in the app's own folder
            "NX2A_WORK" to work.absolutePath,
            // the runtimes folder: the runtime a port builds goes here (one folder per title ID, with its
            // build marker), not into the pack — the launch loads it in place, no per-launch copy
            "NX2A_RUNTIMES" to paths.runtimes.absolutePath,
            "NX2A_PLAYER_API_REVISION" to com.nx.android.domain.PlayerApiRevision.CURRENT.toString(),
            "PYTHONUNBUFFERED" to "1",
            "PYTHONIOENCODING" to "utf-8",
            "DOTNET_SYSTEM_GLOBALIZATION_INVARIANT" to "1",
            "DOTNET_EnableWriteXorExecute" to "0",
            "DOTNET_CLI_TELEMETRY_OPTOUT" to "1",
            "DOTNET_SKIP_FIRST_TIME_EXPERIENCE" to "1",
            // the NuGet packages are restored from the app's own folder: the phone has no network
            "NUGET_PACKAGES" to "${nxHome}/nuget",
        )
    }

    /** Starts a guest program: Python through the loader, the tool itself as it is */
    @Synchronized
    override fun guest(args: List<String>, extra: Map<String, String>): Process {
        refreshTools()
        check(!buildIdChanged()) { "Runtime tools are not ready. Prepare the environment and retry." }
        return startGuest(args, tool, extra)
    }

    private fun startGuest(args: List<String>, cwd: File = tool, extra: Map<String, String> = emptyMap()): Process {
        val cmd = mutableListOf(loader.absolutePath, python.absolutePath)
        cmd.addAll(args)
        val pb = ProcessBuilder(cmd).directory(cwd)
        // one stream for both: a program that fills the pipe of the one we do not read would wait forever
        pb.redirectErrorStream(true)
        pb.environment().putAll(guestEnv())
        pb.environment().putAll(extra)
        return pb.start()
    }

    // ---- the first start: the environment out of the assets -----------------------------------------------

    /**
     * Unpacks the environment. The rootfs and the .NET runtime are tarballs in the assets; the rest is a folder tree.
     *
     * @param onStep what is being unpacked, for the screen
     */
    override fun unpack(onStep: (String) -> Unit) {
        var rootfsDone = false
        try {
            unpackNow(onStep) { rootfsDone = true }
        } catch (e: Exception) {
            // a half unpacked rootfs is worse than none: it goes, so that the next try starts clean. What came
            // out whole stays.
            if (!rootfsDone) rootfs.deleteRecursively()
            throw e
        }
    }

    private fun unpackNow(onStep: (String) -> Unit, rootfsFinished: () -> Unit) {
        fun step(s: String) { Log.i(TAG, "step: $s"); onStep(s) }
        // The environment is already there (the app was updated, not reinstalled): only the check.
        if (ready && dotnet.exists() && marker.exists()) {
            // the environment is already there: only the tool's own scripts and the interpreter paths are new
            copyAssets("tool/tools", File(tool, "tools"))
            step("the interpreter paths")
            patchInterpreters()
            step("the check")
            check()
            return
        }
        if (!python.exists()) {
            step("the Linux environment")
            ctx.assets.open("rootfs.pkg").use { raw ->
                GZIPInputStream(raw).use { gz -> Tar.unpack(gz, rootfs) }
            }
            rootfsFinished()
        }
        if (!File(files, ".nx2a-tool").exists()) {
            step("the tool")
            copyAssets("tool", tool)
            File(files, ".nx2a-tool").writeText("1")
        }
        if (!File(files, ".nx2a-toolchain").exists()) {
            step("the build tools")
            copyAssets("toolchain", File(tool, "toolchain"))
            File(files, ".nx2a-toolchain").writeText("1")
        }
        if (!dotnet.exists()) {
            step("the .NET runtime")
            try {
                // the runtime's own folder: the porter looks for toolchains/dotnet/dotnet
                ctx.assets.open("dotnet.pkg").use { raw ->
                    GZIPInputStream(raw).use { gz -> Tar.unpack(gz, File(nxHome, "toolchains/dotnet")) }
                }
            } catch (e: IOException) {
                // not shipped with the app: the Setup screen downloads it
            }
        }
        // the tool's own scripts are small and change with the app: they are always the ones that are here
        copyAssets("tool/tools", File(tool, "tools"))
        fixGlobalization()
        step("the interpreter paths")
        patchInterpreters()
        step("the check")
        check()
        marker.writeText("1")
    }

    /** The mark that says the environment is unpacked (so an update does not do it again) */
    private val marker: File get() = File(files, ".nx2a-unpacked")

    /** Throws the environment away: the next unpack does everything again */
    override fun reset() {
        marker.delete()
        File(files, ".nx2a-tool").delete()
        File(files, ".nx2a-toolchain").delete()
        // the whole environment goes: what was unpacked with the wrong permissions stays wrong
        rootfs.deleteRecursively()
        tool.deleteRecursively()
        nxHome.deleteRecursively()
        home.deleteRecursively()
        tmp.deleteRecursively()
    }

    /**
     * Runs a guest program to see that the environment works: the loader, the rootfs's Python and the modules.
     * The failure says what is wrong, instead of leaving it to the next step.
     */
    private fun check() {
        val p = startGuest(listOf("-c", "import sys, lz4.block, capstone, os; " +
                "print('guest ok', sys.version.split()[0], 'storage', 'yes' if os.access('/sdcard', os.R_OK) else 'no')"))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        val err = p.errorStream.bufferedReader().readText()
        if (p.exitValue() != 0 || !out.contains("guest ok")) {
            Log.e(TAG, "the guest does not run (exit ${p.exitValue()}): ${(err + out).trim().take(400)}")
            throw IOException("the guest does not run (exit ${p.exitValue()}): ${(err + out).trim().take(400)}")
        }
        lastCheck = out.trim()
        Log.i(TAG, "check: ${out.trim()}")
    }

    /** What the check said (the screen shows it) */
    override var lastCheck: String = ""
        private set

    /**
     * Copies a folder out of the assets. The APK's assets carry no permissions, so the modes come from the file
     * the build wrote (modes.json): the guest's programs have to be executable, or the porter cannot start them.
     */
    private fun copyAssets(path: String, dest: File) {
        val names = ctx.assets.list(path) ?: return
        if (names.isEmpty()) {   // an empty folder in the assets is not listed
            dest.mkdirs(); return
        }
        dest.mkdirs()
        for (name in names) {
            val child = "$path/$name"
            val target = File(dest, name)
            val sub = ctx.assets.list(child)
            if (sub != null && sub.isNotEmpty()) copyAssets(child, target)
            else {
                ctx.assets.open(child).use { src -> target.outputStream().use { src.copyTo(it) } }
                Tar.applyMode(target, modes[child] ?: 420L)
            }
        }
    }

    /** The permissions of the files in the assets (path -> mode), as the build wrote them */
    private val modes: Map<String, Long> by lazy {
        try {
            ctx.assets.open("modes.json").use { raw ->
                val text = raw.readBytes().toString(Charsets.UTF_8)
                val json = org.json.JSONObject(text)
                val out = HashMap<String, Long>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    out[k] = json.getLong(k)
                }
                out
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * The tools that are small and change with the app: the prebuilt C# tools (the extractor, the shader
     * translator), the porter's own scripts and the shared scripts they use. A changed payload is fully
     * deployed and checked before any build can start: a phone that has an older prebuilt translator would try
     * to build one itself, and wait for a network that is not there.
     */
    @Synchronized
    override fun refreshTools() {
        if (!python.exists()) return // The initial environment setup supplies the rootfs.
        // Without the payload there are no modes to write, and the assets hold no tree to copy either:
        // a copy now would strip the execute bit off the tools (the assets have no permissions). A build
        // without the payload leaves the tree alone.
        if (modes.isEmpty()) {
            Log.i(TAG, "the app carries no payload: the tree is left alone")
            return
        }
        try {
            // The build's stamp: when it changes, the tree goes and comes again.
            //
            // The tree has to GO, not just be written over: the port compiles every source it finds
            // (build.py globs dm/android/*.c), so a file the new payload no longer has - a renamed one -
            // would be compiled next to its replacement, and the link would die on duplicate symbols.
            // The stamp is written after the copy, so a copy cut short (the process died) is done again.
            if (buildIdChanged()) {
                Log.i(TAG, "the assets are newer than the tree: it goes and comes again")
                wipeTree()
                copyAssets("tool", tool)
                File(tool, "toolchain").deleteRecursively()
                copyAssets("toolchain", File(tool, "toolchain"))
            } else {
                return
            }
            fixGlobalization()
            patchInterpreters()
            check()
            storeBuildId()
            Log.i(TAG, "the tools were refreshed")
        } catch (e: Exception) {
            Log.e(TAG, "the tools were not refreshed: ${e.message}")
        }
    }

    /**
     * Remove old sources before deployment. The compiler tree is replaced separately.
     */
    private fun wipeTree() {
        for (child in tool.listFiles() ?: emptyArray()) {
            if (child.name == "toolchain") continue
            child.deleteRecursively()
        }
    }

    /**
     * The build's stamp in the assets, against the one the tree was unpacked with.
     *
     * The stamp carries a generation of its own: when the rule changes (the tree goes and comes again
     * instead of only being written over), the generation changes too - so the phones that were updated
     * with the older rule (which left the files the new payload no longer has) do the wipe once, when
     * they install an app with this rule.
     */
    private fun buildIdChanged(): Boolean {
        val want = stampInAssets()
        if (want.isEmpty()) return false      // the assets carry no stamp: nothing to compare
        return want != storedBuildId()
    }

    /** The stamp the assets carry, with the generation in front of it */
    private fun stampInAssets(): String = try {
        val raw = ctx.assets.open("build-id").use { it.readBytes().toString(Charsets.UTF_8).trim() }
        if (raw.isEmpty()) "" else "$STAMP_GEN$raw"
    } catch (e: Exception) {
        ""
    }

    /** The stamp the tree was unpacked with */
    private fun storedBuildId(): String {
        val mark = File(files, ".nx2a-build")
        return if (mark.exists()) mark.readText().trim() else ""
    }

    /** The stamp is written after the copy: a copy that was cut short is done again on the next start */
    private fun storeBuildId() {
        val want = stampInAssets()
        if (want.isEmpty()) return
        File(files, ".nx2a-build").writeText(want)
    }

    /**
     * Rewrites the interpreter of the programs that are started directly (the compiler, the .NET runtime): Android
     * has no /lib, and the rootfs the payload carries was built for another app's path.
     */
    override fun patchInterpreters() {
        Log.i(TAG, "python3.13 before patching: ${python.length()} bytes")
        val script = File(tool, "tools/patch_interp.py")
        if (!script.exists()) throw IOException("The interpreter patch tool is missing")
        val targets = listOf(File(tool, "toolchain"), File(rootfs, "usr/bin"), File(rootfs, "usr/sbin"),
                              File(nxHome, "toolchains"), File(tool, "tools/nx_extract"))
            .filter { it.exists() }
        nxHome.mkdirs()
        for (t in targets) {
            val log = File(nxHome, "patch-${t.name}.log")
            try {
                val pb = ProcessBuilder(loader.absolutePath, python.absolutePath,
                    script.absolutePath, t.absolutePath, interpPath)
                pb.directory(tool)
                pb.redirectErrorStream(true)
                pb.redirectOutput(log)
                pb.environment().putAll(guestEnv())
                val p = pb.start()
                val code = p.waitFor()
                val tail = log.readText().lines().lastOrNull { it.isNotBlank() } ?: ""
                if (code != 0) throw IOException("Interpreter patch failed for ${t.name}: exit $code")
                Log.i(TAG, "patch ${t.absolutePath}: exit $code $tail")
            } catch (e: Exception) {
                Log.e(TAG, "patch ${t.absolutePath}: ${e.message}")
                throw IOException("The build tools could not be prepared", e)
            }
            Log.i(TAG, "python3.13 after patching: ${python.length()} bytes")
        }
        restoreModes()
    }

    /**
     * The permissions of the tool's tree, as the build wrote them: the patch's write may have taken the execute
     * bit away, and a program that cannot be started cannot be patched either.
     */
    /**
     * The .NET runtime on the phone stops itself without ICU (the rootfs has none): the runtime config has to
     * declare the invariant mode, and the files below the folder have to be told.
     */
    private fun fixGlobalization() {
        val script = File(tool, "tools/fix_globalization.py")
        val sdk = File(nxHome, "toolchains/dotnet")
        if (!script.exists() || !sdk.isDirectory) return
        try {
            val p = startGuest(listOf(script.absolutePath, sdk.absolutePath))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            Log.i(TAG, "globalization: ${out.trim()}")
        } catch (e: Exception) {
            Log.e(TAG, "globalization: ${e.message}")
        }
    }

    private fun restoreModes() {
        var n = 0
        for ((path, mode) in modes) {
            val f = if (path.startsWith("tool/")) File(tool, path.removePrefix("tool/")) else File(tool, path)
            if (!f.exists()) continue
            val now = try { f.canExecute() } catch (e: Exception) { false }
            if (now && (mode and 64L == 0L)) continue
            Tar.applyMode(f, mode)
            n++
        }
        Log.i(TAG, "permissions restored for $n file(s)")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EnvironmentDataModule {
    @Binds
    @Singleton
    abstract fun bindEnvironmentSetup(impl: Env): EnvironmentSetup

    @Binds
    @Singleton
    abstract fun bindGuestProcessLauncher(impl: Env): GuestProcessLauncher
}
