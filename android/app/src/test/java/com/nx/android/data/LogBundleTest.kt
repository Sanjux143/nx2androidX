package com.nx.android.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * The log bundle: what "share the log" sends. The device's facts, the app's log, the porter's stage logs
 * (the build folder's logs: the host's build, the Oodle decoder's run) and the build history's logs.
 */
class LogBundleTest {

    private fun tmp(name: String): File = Files.createTempDirectory("nx2a-bundle").toFile()

    /** a file in a fresh folder */
    private fun tmpFile(name: String, body: String): File = File(tmp(name), name).apply { writeText(body) }

    /** a stage log of the porter: <work>/<title>_build/logs/<name> */
    private fun stageLog(work: File, title: String, name: String, body: String): File {
        val dir = File(File(work, "${title}_build"), "logs")
        dir.mkdirs()
        return File(dir, name).apply { writeText(body) }
    }

    @After
    fun clean() {
        AppLog.clear()
    }

    @Test
    fun theBundleHoldsTheDeviceTheLogAndTheStageLogs() {
        val work = tmp("work")
        val out = tmp("out")
        val log = tmpFile("log.txt", "the app's own log\n")
        AppLog.init(log)
        stageLog(work, "01001300232F4000", "android_oodle_job.log", ">>> nninitStartup\n[RED] refused\n")
        stageLog(work, "01001300232F4000", "crash.log", "Traceback\n")

        val zip = LogBundle.build("device: test phone\n", "0.3.0-alpha07", work, emptyList(), out)
        assertNotNull("the bundle is packed", zip)
        val entries = ZipFile(zip!!).use { z ->
            z.entries().toList().associate { it.name to z.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
        }
        assertTrue("the device's facts are in it: ${entries.keys}", entries.containsKey("device.txt"))
        assertTrue("the device's facts are in it", entries["device.txt"]!!.contains("test phone"))
        assertTrue("the app's log is in it: ${entries.keys}", entries.containsKey("log.txt"))
        assertTrue("the app's log is in it", entries["log.txt"]!!.contains("the app's own log"))
        assertTrue("the stage log is in it: ${entries.keys}",
            entries.containsKey("stage-logs/01001300232F4000_build/android_oodle_job.log"))
        assertTrue("the stage log is in it",
            entries["stage-logs/01001300232F4000_build/android_oodle_job.log"]!!.contains("refused"))
        assertTrue("the crash log is in it: ${entries.keys}",
            entries.containsKey("stage-logs/01001300232F4000_build/crash.log"))
    }

    @Test
    fun theBuildHistorysLogsGoUnderBuilds() {
        val work = tmp("work")
        val out = tmp("out")
        val build = tmpFile("build.log", "the build's log\n")
        AppLog.init(tmpFile("log.txt", ""))

        val zip = LogBundle.build("device\n", "0.3.0-alpha07", work, listOf("game-abcdef12.log" to build), out)
        assertNotNull("the bundle is packed", zip)
        val names = ZipFile(zip!!).use { z -> z.entries().toList().map { it.name } }
        assertTrue("the build's log is in it: $names", names.contains("builds/game-abcdef12.log"))
    }

    @Test
    fun aBigStageLogIsCutToItsTail() {
        val work = tmp("work")
        val out = tmp("out")
        AppLog.init(tmpFile("log.txt", ""))
        val big = stageLog(work, "01001300232F4000", "extract.log", "x".repeat((LogBundle.STAGE_LIMIT + 4096).toInt()))
        assertTrue("the stage log is big", big.length() > LogBundle.STAGE_LIMIT)

        val zip = LogBundle.build("device\n", "0.3.0-alpha07", work, emptyList(), out)
        assertNotNull("the bundle is packed", zip)
        val entry = ZipFile(zip!!).use { z ->
            z.getInputStream(z.getEntry("stage-logs/01001300232F4000_build/extract.log")).readBytes()
        }
        assertTrue("the entry is cut to the cap: ${entry.size}", entry.size <= LogBundle.STAGE_LIMIT + 200)
        assertTrue("the cut is said in the entry", String(entry, Charsets.UTF_8).contains("are cut off"))
    }

    @Test
    fun nothingToShareGivesNoBundle() {
        val work = tmp("work")
        val out = tmp("out")
        AppLog.init(tmpFile("log.txt", ""))   // an empty log file
        assertNull("an empty log and no stage logs give no bundle",
            LogBundle.build("device\n", "0.3.0-alpha07", work, emptyList(), out))
    }

    @Test
    fun theNameCarriesTheVersionAndWhatItIsAbout() {
        val work = tmp("work")
        val out = tmp("out")
        AppLog.init(tmpFile("log.txt", "something\n"))
        val zip = LogBundle.build("device\n", "0.3.0-alpha07", work, emptyList(), out, "game-abcdef12")
        assertNotNull("the bundle is packed", zip)
        val name = zip!!.name
        assertTrue("the version is in the name: $name", name.contains("0.3.0-alpha07"))
        assertTrue("what it is about is in the name: $name", name.contains("game-abcdef12"))
        assertTrue("the name is a zip: $name", name.endsWith(".zip"))
    }
}
