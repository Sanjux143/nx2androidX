package com.nx.android.data.saves

import com.nx.android.data.SaveBackup
import com.nx.android.domain.saves.SaveOperationException
import com.nx.android.domain.saves.SaveSource
import com.nx.android.domain.saves.SaveTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveRepositoryImplTest {
    @Test
    fun `an imported package replaces the live save`() = runBlocking {
        val fixture = Fixture()
        try {
            val live = SaveBackup.liveDir(fixture.platform.filesDir, TITLE_ID)
            live.mkdirs()
            File(live, "slot.dat").writeText("current")
            val packageFile = fixture.packageFor(TITLE_ID, "slot.dat" to "imported")

            val result = fixture.repository.import(TITLE_ID, fixture.source(packageFile))

            assertTrue(result.isSuccess)
            assertEquals("imported", File(live, "slot.dat").readText())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `an exported package can be imported back`() = runBlocking {
        val fixture = Fixture()
        try {
            val live = SaveBackup.liveDir(fixture.platform.filesDir, TITLE_ID)
            live.mkdirs()
            File(live, "slot.dat").writeText("progress")
            val target = CollectingTarget()

            val exported = fixture.repository.export(TITLE_ID, "game", target)

            assertTrue(exported.isSuccess)
            val name = fixture.repository.suggestedPackageName(TITLE_ID, "game")
            assertTrue(name, name.endsWith(".zip"))
            assertEquals(TITLE_ID, fixture.repository.titleIdInPackage(name))
            File(live, "slot.dat").writeText("something else")
            val imported = fixture.repository.import(TITLE_ID, fixture.source(name, target.bytes()))

            assertTrue(imported.isSuccess)
            assertEquals("progress", File(live, "slot.dat").readText())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a game without a save has nothing to export`() = runBlocking {
        val fixture = Fixture()
        try {
            SaveBackup.liveDir(fixture.platform.filesDir, TITLE_ID).mkdirs()

            val result = fixture.repository.export(TITLE_ID, "game", CollectingTarget())

            assertEquals(SaveOperationException.Reason.NO_SAVES,
                (result.exceptionOrNull() as SaveOperationException).reason)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `wrong title and traversal packages are rejected without changing live save`() = runBlocking {
        val fixture = Fixture()
        try {
            val live = SaveBackup.liveDir(fixture.platform.filesDir, TITLE_ID)
            live.mkdirs()
            File(live, "slot.dat").writeText("keep")
            val wrongTitle = fixture.packageFor(OTHER_TITLE_ID, "slot.dat" to "wrong")
            val wrongResult = fixture.repository.import(TITLE_ID, fixture.source(wrongTitle))
            assertTrue(wrongResult.exceptionOrNull() is SaveOperationException)
            assertEquals(SaveOperationException.Reason.TITLE_MISMATCH,
                (wrongResult.exceptionOrNull() as SaveOperationException).reason)

            val traversal = fixture.root.resolve("Downloads/escape.zip")
            traversal.parentFile?.mkdirs()
            ZipOutputStream(traversal.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("tree/../../escape.txt"))
                zip.write("bad".toByteArray())
                zip.closeEntry()
            }
            val traversalResult = fixture.repository.import(TITLE_ID, fixture.source(traversal))
            assertTrue(traversalResult.exceptionOrNull() is SaveBackup.Refused)
            assertFalse(fixture.root.resolve("Downloads/escape.txt").exists())
            assertEquals("keep", File(live, "slot.dat").readText())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a broken package is refused and the live save stays`() = runBlocking {
        val fixture = Fixture()
        try {
            val live = SaveBackup.liveDir(fixture.platform.filesDir, TITLE_ID)
            live.mkdirs()
            File(live, "slot.dat").writeText("keep")
            val broken = fixture.root.resolve("game-$TITLE_ID-20261112-120000.zip")
            broken.writeText("this is not a zip archive")

            val result = fixture.repository.import(TITLE_ID, fixture.source(broken))

            assertTrue(result.exceptionOrNull() is SaveBackup.Refused)
            assertEquals("keep", File(live, "slot.dat").readText())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `an invalid title id prevents save access`() = runBlocking {
        val fixture = Fixture()
        try {
            val invalid = fixture.repository.export("../escape", "game", CollectingTarget())
            assertEquals(SaveOperationException.Reason.INVALID_TITLE_ID,
                (invalid.exceptionOrNull() as SaveOperationException).reason)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `cancellation during replacement waits for the save swap to finish`() = runBlocking {
        val fixture = Fixture()
        try {
            val live = SaveBackup.liveDir(fixture.platform.filesDir, TITLE_ID)
            live.mkdirs()
            File(live, "slot.dat").writeText("current")
            val packageFile = fixture.packageFor(TITLE_ID, "slot.dat" to "imported")
            val enteredSwap = CompletableDeferred<Unit>()
            val finishSwap = CompletableDeferred<Unit>()
            val swap = object : SaveSwap {
                override suspend fun install(tree: File, device: File?, live: File, liveDevice: File) {
                    enteredSwap.complete(Unit)
                    finishSwap.await()
                    SaveBackup.swapIn(tree, device, live, liveDevice)
                }
            }
            val repository = SaveRepositoryImpl(fixture.platform, Dispatchers.Unconfined, swap)
            val job = async { repository.import(TITLE_ID, fixture.source(packageFile)) }

            enteredSwap.await()
            job.cancel()
            finishSwap.complete(Unit)
            job.join()

            assertTrue(job.isCancelled)
            assertEquals("imported", File(live, "slot.dat").readText())
        } finally {
            fixture.close()
        }
    }

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("nx2a-save-repo").toFile()
        val platform = FakeSavePlatform(root)
        val repository = SaveRepositoryImpl(platform, Dispatchers.Unconfined, FileSaveSwap())

        fun packageFor(titleId: String, entry: Pair<String, String>): File {
            val tree = root.resolve("source-${titleId}")
            tree.mkdirs()
            File(tree, entry.first).writeText(entry.second)
            val dest = root.resolve("game-$titleId-20261112-120000.zip")
            SaveBackup.writePackage(tree, null, tree.name, dest.outputStream())
            return dest
        }

        /** the package as the file manager hands it over: a name and a stream */
        fun source(file: File): SaveSource = source(file.name, file.readBytes())

        fun source(name: String, bytes: ByteArray): SaveSource = object : SaveSource {
            override val name: String = name
            override fun open(): InputStream = ByteArrayInputStream(bytes)
        }

        override fun close() { root.deleteRecursively() }
    }

    /** the place the player picked in the file manager: the bytes land here */
    private class CollectingTarget : SaveTarget {
        private val buffer = ByteArrayOutputStream()
        override fun open(): OutputStream = buffer
        fun bytes(): ByteArray = buffer.toByteArray()
    }

    private class FakeSavePlatform(private val root: File) : SavePlatform {
        override val filesDir: File = File(root, "files")
        override val cacheDir: File = File(root, "cache")
    }

    private companion object {
        const val TITLE_ID = "0100000000010000"
        const val OTHER_TITLE_ID = "0100000000018000"
    }
}
