package com.nx.android.appstate

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nx.android.BuildConfig
import com.nx.android.data.AppLog
import com.nx.android.data.AppLocale
import com.nx.android.data.PrefsStore
import com.nx.android.data.PortRunner
import com.nx.android.data.PhantomFix
import com.nx.android.data.ProfilesDownloader
import com.nx.android.data.UpdateChecker
import com.nx.android.domain.DoctorOutput
import com.nx.android.domain.EnvCheck
import com.nx.android.domain.PhantomCheck
import com.nx.android.domain.ProfilesPackage
import com.nx.android.domain.environment.EnvironmentSetup
import com.nx.android.domain.environment.GuestProcessLauncher
import com.nx.android.core.storage.AppPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * The environment's state: the two cheap facts the top bar's chip shows, and the doctor's rows.
 *
 * The facts are read from the filesystem, so they are read again whenever the screen comes back and whenever
 * the player changed one of them (the environment was prepared, the keys were replaced). The doctor runs a
 * python inside the rootfs - seconds, not milliseconds - so it only runs when the player asks for it, and its
 * rows stay until the next run.
 *
 * Android's limit on the app's child processes is a third fact: reading it runs a program, so it is read on
 * a thread of its own and lands here when it is there.
 */
@Singleton
class EnvCheckState @Inject constructor(
    private val env: EnvironmentSetup,
    private val guestLauncher: GuestProcessLauncher,
    private val prefs: PrefsStore,
    private val locale: AppLocale,
    private val paths: AppPaths,
    @ApplicationContext private val app: Context,
) {

    /** The environment is prepared (the rootfs, the tool and the compiler are there) */
    var envReady by mutableStateOf(false); private set

    /** The keys file the app uses (the setup and the settings page write the same one) */
    var keysPath by mutableStateOf(""); private set

    /** The keys file is there */
    var keysOk by mutableStateOf(false); private set

    /** The facts have been read: before that, the chip says it is checking */
    var known by mutableStateOf(false); private set

    /**
     * The machine's firmware has been imported.
     *
     * It is optional (only a game that reads the console's system data needs it), so a missing one only
     * colours the chip amber: the app works without it.
     */
    var firmwareImported by mutableStateOf(true); private set

    /**
     * The chip's verdict: the two cheap facts and the child-process limit's state.
     *
     * It is read out of the facts instead of being kept, because the limit's state arrives later than the
     * other two (it is read on a thread of its own): a kept value would go stale and the chip would stay
     * green after the limit was found to be on.
     */
    val chip: EnvCheck.Chip
        get() = if (!known) EnvCheck.Chip.CHECKING
        else EnvCheck.chip(envReady, keysOk, if (phantomKnown) phantom else null, firmwareImported, profilesCount > 0)

    /** The doctor is running */
    var running by mutableStateOf(false); private set

    /** The doctor has run: its rows are below */
    var checked by mutableStateOf(false); private set

    /** The rows the doctor printed */
    var rows by mutableStateOf(listOf<EnvCheck.Row>()); private set

    /** The doctor ran, but nothing came out that could be read */
    var noOutput by mutableStateOf(false); private set

    /** Android's limit on the app's child processes: the porter is one of them */
    var phantom by mutableStateOf(PhantomCheck.Status.UNKNOWN); private set

    /** The limit's state has been read: before that, the row says nothing */
    var phantomKnown by mutableStateOf(false); private set

    /**
     * The game profiles the player imported.
     *
     * The app does not carry them (they are half a gigabyte of shader tables): the player imports a package into their own
     * folder, and the porter finds them there. The count and the package's revision come from the receipt the import wrote.
     *
     * The app carries the manifest of the package this build made (its name, its digest), so it can say whether the player
     * imported that one: the digests are compared, not the versions - a package that is not this build's is not wrong, it is
     * just another one, and the row says so without nagging.
     */
    var profilesCount by mutableStateOf(0); private set
    var profilesLabel by mutableStateOf(""); private set

    /**
     * The imported profiles that are ported to Android (they carry an android.tsv).
     *
     * The package holds more than that: most of its profiles are upstream's iOS ones, which the player can try
     * experimentally but which are not ported. The count is read from the layer itself, so it is right for every
     * package the player imported, not only for the ones this build made.
     */
    var profilesPorted by mutableStateOf(0); private set

    /** The package this build made: its name, its digest and its revision (empty when the app carries none) */
    var profilesExpected by mutableStateOf(""); private set
    var profilesExpectedName by mutableStateOf(""); private set

    /** The player's package is not this build's: there is a newer one (or an older one) */
    var profilesDiffer by mutableStateOf(false); private set

    /** A package is being imported */
    var importingProfiles by mutableStateOf(false); private set

    /** The last import's own line: what went wrong, when it did */
    var profilesMessage by mutableStateOf(""); private set

    /** The online update of the package: idle, on its way, or it did not work out */
    var packageUpdate by mutableStateOf<ProfilesPackage.Update>(ProfilesPackage.Update.Idle); private set

    /** A download is running: the next one does not start */
    @Volatile private var packageDownloading = false

    /** The download was called off (the player closed the dialog) */
    @Volatile private var packageCancelled = false

    /** A read of the limit is running: another one does not start */
    @Volatile private var phantomReading = false

    /** Reads the facts again */
    fun refresh() {
        envReady = env.ready
        keysPath = prefs.keys
        keysOk = keysPath.isNotBlank() && File(keysPath).isFile()
        firmwareImported = prefs.str(PrefsStore.FIRMWARE).isNotBlank()
        known = true
        readExpectedProfiles()
        readProfiles()
        refreshPhantom()
    }

    /**
     * The imported profiles, read from the receipt the import wrote.
     *
     * The receipt is the porter's own manifest.json: {revision, commit, count, ids, sha256}. The app reads the file itself - the
     * layer is in the app's own folder, which the guest sees under the same path.
     */
    fun refreshProfiles() = readProfiles()

    private fun readProfiles() {
        profilesCount = 0
        profilesLabel = ""
        profilesDiffer = false
        profilesPorted = 0
        val layer = File(paths.nxHome, "profiles")
        val receipt = File(layer, "manifest.json")
        if (!receipt.isFile) return
        val text = runCatching { receipt.readText() }.getOrNull() ?: return
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        profilesCount = json.optInt("count", 0)
        profilesLabel = "r${json.optInt("revision", 0)}"
        // the digests: the player's package against this build's. A different one is not wrong, it is another one.
        val theirs = json.optString("sha256", "")
        profilesDiffer = profilesExpected.isNotEmpty() && theirs.isNotEmpty() && theirs != profilesExpected
        // the ported ones: the profiles that carry an android.tsv (the layer mirrors the game's own tree)
        profilesPorted = layer.listFiles()?.count { it.isDirectory && File(it, "android.tsv").isFile } ?: 0
    }

    /**
     * The package this build made, read from the app's own assets (profiles.json: the name, the digest, the revision).
     *
     * The app does not carry the package itself - only this, a few hundred bytes - so the player can be told whether the
     * package they imported is the one of this build.
     */
    private fun readExpectedProfiles() {
        profilesExpected = ""
        profilesExpectedName = ""
        val text = runCatching {
            app.assets.open("profiles.json").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        profilesExpected = json.optString("sha256", "")
        profilesExpectedName = json.optString("name", "")
    }

    /**
     * Imports a package of game profiles.
     *
     * The package is copied into the app's own folder first: the guest sees the app's files, not the phone's storage, so a
     * package picked on the SD card has to be copied before the porter can read it. The porter does the import (it knows the
     * package's layout), and its own lines say what went wrong.
     */
    fun importProfiles(packageFile: File, onDone: (Boolean, String) -> Unit) {
        if (importingProfiles) return
        importingProfiles = true
        profilesMessage = ""
        Thread {
            val copied = runCatching { copyToImportDir(packageFile) }.getOrNull()
            if (copied == null) {
                importingProfiles = false
                profilesMessage = "the package could not be read"
                onDone(false, profilesMessage)
                return@Thread
            }
            importPackage(copied, onDone)
        }.start()
    }

    /**
     * Fetches the package of this build over the network and imports it.
     *
     * The package is the release's second artifact, so it is where the release that carries this build's version is.
     * When that release does not carry one (the package came with a later build), the newest release is asked instead:
     * its package is a valid one, and the row says it is not this build's.
     */
    fun updateProfiles(onDone: (Boolean) -> Unit) {
        if (packageDownloading) return
        packageDownloading = true
        packageCancelled = false
        postUpdate(ProfilesPackage.Update.Downloading(0f))
        thread(name = "profiles-update") {
            val dest = File(paths.files, "import/profiles.zip")
            val candidates = packageCandidates()
            var failure = if (candidates.isEmpty()) "the release does not carry a package yet" else "no answer"
            for ((url, sha) in candidates) {
                if (packageCancelled) break
                try {
                    val file = ProfilesDownloader.download(url, dest, sha,
                        { p -> postUpdate(ProfilesPackage.Update.Downloading(p)) }, { packageCancelled }).getOrThrow()
                    postUpdate(ProfilesPackage.Update.Idle)
                    importPackage(file) { ok, _ ->
                        packageDownloading = false
                        onDone(ok)
                    }
                    return@thread
                } catch (e: Exception) {
                    failure = e.message ?: failure
                }
            }
            packageDownloading = false
            if (packageCancelled) postUpdate(ProfilesPackage.Update.Idle)
            else postUpdate(ProfilesPackage.Update.Failed(failure))
            post { onDone(false) }
        }
    }

    /** Calls the download off (the player closed the dialog) */
    fun cancelPackageUpdate() {
        packageCancelled = true
    }

    /**
     * Where the package may be.
     *
     * This build's release first: its package is the one this build made, so the digest in the app's manifest checks
     * it. Then the newest release: its package is a valid one, but not this build's (its name is not even the one
     * this build knows), so it is taken as it comes.
     */
    private fun packageCandidates(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val name = profilesExpectedName
        if (name.isNotEmpty()) {
            out += ProfilesPackage.assetUrl(ProfilesPackage.tag(BuildConfig.VERSION_NAME), name) to profilesExpected
        }
        val latest = runCatching { runBlocking { UpdateChecker.latestTag() }.getOrThrow() }.getOrNull()
        if (latest != null) {
            for (n in runCatching { ProfilesDownloader.packagesOf(latest) }.getOrDefault(emptyList())) {
                val url = ProfilesPackage.assetUrl(latest, n)
                if (out.none { it.first == url }) out += url to ""
            }
        }
        return out
    }

    /** The update's state belongs to the screen: it is set on the main thread */
    private fun postUpdate(update: ProfilesPackage.Update) = post { packageUpdate = update }

    /**
     * The main thread.
     *
     * The porter runs in a thread of its own and answers there, so what it hands back arrives off the main
     * thread: a toast made there is a crash, and the screen's own state does not belong there either. Everything
     * that goes back to the caller goes through here.
     */
    private fun post(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    /** The copy into the app's own folder: the guest sees the app's files, not the phone's storage */
    private fun copyToImportDir(packageFile: File): File {
        val dir = File(paths.files, "import")
        dir.mkdirs()
        val dst = File(dir, "profiles.zip")
        packageFile.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        return dst
    }

    /** The porter does the import: it knows the package's layout, and its own lines say what went wrong */
    private fun importPackage(packageFile: File, onDone: (Boolean, String) -> Unit) {
        val buf = StringBuilder()
        PortRunner(env, guestLauncher, locale) { line -> buf.appendLine(line); AppLog.line(line) }
            .importProfiles(packageFile.absolutePath) { code ->
                importingProfiles = false
                readProfiles()
                val ok = code == 0
                profilesMessage = if (ok) "" else buf.toString().trim().lines().lastOrNull { it.isNotBlank() } ?: ""
                // a package that got in clears the last download's complaint
                if (ok) postUpdate(ProfilesPackage.Update.Idle)
                // the porter answers in its own thread: the caller (a toast, a screen) wants the main one
                post { onDone(ok, profilesMessage) }
            }
    }

    /**
     * Reads Android's limit on the app's child processes.
     *
     * Reading it runs `device_config`, so it happens on a thread of its own: the screen shows the row as
     * soon as the answer is there. A read that is already running is left alone - the answer does not
     * change between two reads.
     */
    fun refreshPhantom() {
        if (phantomReading) return
        phantomReading = true
        thread(name = "phantom-check") {
            val s = try {
                PhantomFix.status(app)
            } catch (e: Exception) {
                AppLog.line("[RED] the child-process limit could not be read: ${e.message}")
                PhantomCheck.Status.UNKNOWN
            }
            Handler(Looper.getMainLooper()).post {
                phantom = s
                phantomKnown = true
                phantomReading = false
            }
        }
    }

    /**
     * Runs the doctor's check and puts its rows here.
     *
     * The raw output goes to the log: the settings page's own output box is gone, and the log is where the
     * output of a broken environment can be looked at (and shared).
     */
    fun runDoctor() {
        if (running) return
        running = true
        checked = false
        noOutput = false
        rows = listOf()
        val out = StringBuilder()
        PortRunner(env, guestLauncher, locale) { line -> out.appendLine(line); AppLog.line(line) }
            .doctor(keysPath, json = true) {
                running = false
                val parsed = DoctorOutput.rows(out.toString())
                if (parsed == null) {
                    noOutput = true
                } else {
                    rows = EnvCheck.rows(parsed)
                }
                checked = true
            }
    }
}
